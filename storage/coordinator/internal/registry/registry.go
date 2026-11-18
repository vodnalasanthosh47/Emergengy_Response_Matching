// Package registry is the coordinator's in-memory view of the storage cluster: which
// nodes exist, which shard each holds, which one is the shard's primary, and which
// are alive. It implements node registration and heartbeats, failure detection,
// primary election and failover.
//
// Node lifecycle (per node):
//
//	register ──► SYNCING ──(syncer copied the shard)──► ACTIVE
//	    │                                                 │
//	    └──► ELECTING (shard had no primary) ──elected──► ACTIVE PRIMARY
//	any state ──(3 missed heartbeats, or a failed call)──► DOWN ──(node re-registers)──► …
//
// Only ACTIVE nodes serve reads or can be promoted, because only they are known to
// hold every acknowledged write of their shard (the coordinator replicates each
// write to all ACTIVE and SYNCING nodes before acknowledging it).
//
// The registry is soft state. After a coordinator restart every node gets 404 on
// its next heartbeat, registers again, and each shard elects the registering node
// with the newest data.
package registry

import (
	"cmp"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
)

// Role of a node within its shard.
type Role string

const (
	RolePrimary Role = "PRIMARY"
	RoleReplica Role = "REPLICA"
)

// State of a node.
type State string

const (
	StateElecting State = "ELECTING" // registered while its shard had no primary
	StateSyncing  State = "SYNCING"  // receives writes, being brought up to date; no reads
	StateActive   State = "ACTIVE"   // complete copy of the shard; serves reads
	StateDown     State = "DOWN"     // missed heartbeats or failed a call; excluded
)

// States lists every state, for metrics.
var States = []State{StateElecting, StateSyncing, StateActive, StateDown}

var (
	// ErrUnknownNode answers a heartbeat from a node that must register (again).
	ErrUnknownNode = errors.New("unknown node: register again")
	// ErrInvalidNode rejects a malformed registration.
	ErrInvalidNode = errors.New("invalid node info")
)

// Node is a snapshot of one node's entry. Callers always get copies.
type Node struct {
	ID           string    `json:"nodeId"`
	Shard        int       `json:"shardId"`
	URL          string    `json:"url"`
	Role         Role      `json:"role"`
	State        State     `json:"state"`
	LastSeen     time.Time `json:"lastSeen"`
	MaxUpdatedAt time.Time `json:"maxUpdatedAt"`
	// Epoch identifies one registration of the node. Callers pass it back when they
	// report a failure or finish a sync, so a stale report about an earlier
	// incarnation can never affect a node that has since re-registered.
	Epoch uint64 `json:"epoch"`
}

// SyncTask asks the replication syncer to bring a SYNCING node up to date.
type SyncTask struct {
	NodeID string
	Epoch  uint64
}

// Config configures a Registry.
type Config struct {
	Shards           int
	HeartbeatTimeout time.Duration // no heartbeat for this long → DOWN
	ElectionWindow   time.Duration // how long a primary-less shard waits for nodes
	Now              func() time.Time
	Log              *slog.Logger
}

// Registry is safe for concurrent use.
type Registry struct {
	cfg       Config
	mu        sync.Mutex
	nodes     map[string]*Node
	elections []time.Time // per shard: when the open election closes; zero = none
	epoch     uint64
	syncTasks chan SyncTask
	failovers atomic.Uint64
}

// New creates an empty registry.
func New(cfg Config) *Registry {
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	return &Registry{
		cfg:       cfg,
		nodes:     make(map[string]*Node),
		elections: make([]time.Time, cfg.Shards),
		// Large enough that it never fills in practice (there are a handful of nodes);
		// queueSyncLocked must not block while mu is held.
		syncTasks: make(chan SyncTask, 1024),
	}
}

// Shards returns the number of shards.
func (r *Registry) Shards() int { return r.cfg.Shards }

// SyncTasks delivers the nodes that need a recovery sync.
func (r *Registry) SyncTasks() <-chan SyncTask { return r.syncTasks }

// Failovers counts primary promotions that replaced a failed primary.
func (r *Registry) Failovers() uint64 { return r.failovers.Load() }

// Register adds or replaces a node and returns its role. A node always (re)joins as
// a replica: SYNCING if its shard has a primary, otherwise ELECTING.
func (r *Registry) Register(info model.NodeInfo) (Role, error) {
	if info.NodeID == "" || info.URL == "" || info.ShardID < 0 || info.ShardID >= r.cfg.Shards {
		return "", fmt.Errorf("%w: need nodeId, url and shardId in [0,%d)", ErrInvalidNode, r.cfg.Shards)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.cfg.Now()

	// A node that restarts faster than the failure detector re-registers while still
	// listed as primary. It has just lost its in-memory state and may have missed
	// writes, so hand the primary role to an ACTIVE replica first.
	if old, ok := r.nodes[info.NodeID]; ok && old.State != StateDown {
		r.markDownLocked(old, "re-registered")
	}

	r.epoch++
	n := &Node{ID: info.NodeID, Shard: info.ShardID, URL: info.URL, Role: RoleReplica,
		LastSeen: now, MaxUpdatedAt: info.MaxUpdatedAt, Epoch: r.epoch}
	r.nodes[n.ID] = n

	if _, ok := r.primaryLocked(n.Shard); ok {
		n.State = StateSyncing
		r.queueSyncLocked(n)
	} else {
		n.State = StateElecting
		if r.elections[n.Shard].IsZero() {
			r.elections[n.Shard] = now.Add(r.cfg.ElectionWindow)
			r.cfg.Log.Info("shard has no primary: election opened", "shard", n.Shard, "window", r.cfg.ElectionWindow)
		}
	}
	r.cfg.Log.Info("node registered", "node", n.ID, "shard", n.Shard, "url", n.URL, "state", n.State,
		"max_updated_at", n.MaxUpdatedAt)
	return n.Role, nil
}

// Heartbeat records that a node is alive. Unknown or DOWN nodes get ErrUnknownNode
// and must register again (which resyncs them).
func (r *Registry) Heartbeat(info model.NodeInfo) (Role, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	n, ok := r.nodes[info.NodeID]
	if !ok || n.State == StateDown || n.Shard != info.ShardID || n.URL != info.URL {
		return "", ErrUnknownNode
	}
	n.LastSeen = r.cfg.Now()
	n.MaxUpdatedAt = info.MaxUpdatedAt
	return n.Role, nil
}

// Sweep runs failure detection and closes elections whose window has passed. Call
// it periodically (RunSweeper).
func (r *Registry) Sweep() {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.cfg.Now()
	for _, n := range r.sortedLocked() {
		if n.State != StateDown && now.Sub(n.LastSeen) > r.cfg.HeartbeatTimeout {
			r.markDownLocked(n, "heartbeat timeout")
		}
	}
	for shard, closes := range r.elections {
		if !closes.IsZero() && !now.Before(closes) {
			r.closeElectionLocked(shard)
		}
	}
}

// MarkDown takes a node out of service after a failed call, if the report concerns
// its current registration. A primary is replaced immediately.
func (r *Registry) MarkDown(id string, epoch uint64, reason string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if n, ok := r.nodes[id]; ok && n.Epoch == epoch && n.State != StateDown {
		r.markDownLocked(n, reason)
	}
}

// SetActive marks a SYNCING node ACTIVE once the syncer has copied its shard. It
// reports false (and does nothing) if the node re-registered or went down meanwhile.
func (r *Registry) SetActive(t SyncTask) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	n, ok := r.nodes[t.NodeID]
	if !ok || n.Epoch != t.Epoch || n.State != StateSyncing {
		return false
	}
	n.State = StateActive
	r.cfg.Log.Info("node synchronised: ACTIVE", "node", n.ID, "shard", n.Shard)
	return true
}

// StillSyncing reports whether t still describes a SYNCING node.
func (r *Registry) StillSyncing(t SyncTask) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	n, ok := r.nodes[t.NodeID]
	return ok && n.Epoch == t.Epoch && n.State == StateSyncing
}

// Get returns a node by ID.
func (r *Registry) Get(id string) (Node, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	n, ok := r.nodes[id]
	if !ok {
		return Node{}, false
	}
	return *n, true
}

// Primary returns the shard's primary, if it has a usable one.
func (r *Registry) Primary(shard int) (Node, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	n, ok := r.primaryLocked(shard)
	if !ok {
		return Node{}, false
	}
	return *n, true
}

// IsPrimary reports whether node id, in registration epoch, is still its shard's
// usable primary. A write is acknowledged only if this holds after it was replicated:
// otherwise the node was replaced while the write was in flight, and the write may
// exist only on a node that no longer serves the shard.
func (r *Registry) IsPrimary(id string, epoch uint64) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	n, ok := r.nodes[id]
	if !ok || n.Epoch != epoch {
		return false
	}
	p, ok := r.primaryLocked(n.Shard)
	return ok && p == n
}

// Readable returns the nodes that may serve reads for shard: the primary first,
// then ACTIVE replicas. Never SYNCING, ELECTING or DOWN nodes.
func (r *Registry) Readable(shard int) []Node {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []Node
	if p, ok := r.primaryLocked(shard); ok {
		out = append(out, *p)
	}
	for _, n := range r.sortedLocked() {
		if n.Shard == shard && n.Role == RoleReplica && n.State == StateActive {
			out = append(out, *n)
		}
	}
	return out
}

// ReplicaTargets returns the replicas every write of shard must reach: ACTIVE ones,
// and SYNCING ones (so a recovering node never misses a write made during its sync).
func (r *Registry) ReplicaTargets(shard int) []Node {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []Node
	for _, n := range r.sortedLocked() {
		if n.Shard == shard && n.Role == RoleReplica && (n.State == StateActive || n.State == StateSyncing) {
			out = append(out, *n)
		}
	}
	return out
}

// Snapshot returns every node, ordered by shard, then primary first, then ID.
func (r *Registry) Snapshot() []Node {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]Node, 0, len(r.nodes))
	for _, n := range r.sortedLocked() {
		out = append(out, *n)
	}
	slices.SortStableFunc(out, func(a, b Node) int {
		if c := cmp.Compare(a.Shard, b.Shard); c != 0 {
			return c
		}
		return cmp.Compare(rolePriority(a.Role), rolePriority(b.Role))
	})
	return out
}

// primaryLocked returns the shard's primary if it is ACTIVE and no election is open.
func (r *Registry) primaryLocked(shard int) (*Node, bool) {
	if !r.elections[shard].IsZero() {
		return nil, false
	}
	for _, n := range r.nodes {
		if n.Shard == shard && n.Role == RolePrimary && n.State == StateActive {
			return n, true
		}
	}
	return nil, false
}

func (r *Registry) markDownLocked(n *Node, reason string) {
	wasPrimary := n.Role == RolePrimary
	n.State, n.Role = StateDown, RoleReplica
	r.cfg.Log.Warn("node DOWN", "node", n.ID, "shard", n.Shard, "reason", reason, "was_primary", wasPrimary)
	if wasPrimary {
		r.promoteLocked(n.Shard)
	}
}

// promoteLocked replaces a failed primary with an ACTIVE replica. Every ACTIVE
// replica holds every acknowledged write, so any of them is safe; the newest data
// breaks ties (then the ID, for determinism). With none, the shard stays without a
// primary (its calls get 503) until a node with the data registers and is elected.
func (r *Registry) promoteLocked(shard int) {
	var best *Node
	for _, n := range r.sortedLocked() {
		if n.Shard == shard && n.State == StateActive && n.Role == RoleReplica &&
			(best == nil || n.MaxUpdatedAt.After(best.MaxUpdatedAt)) {
			best = n
		}
	}
	if best == nil {
		r.cfg.Log.Error("shard has no ACTIVE replica to promote: shard unavailable until a node rejoins", "shard", shard)
		return
	}
	best.Role = RolePrimary
	r.failovers.Add(1)
	r.cfg.Log.Warn("failover: replica promoted to PRIMARY", "shard", shard, "node", best.ID)
}

// closeElectionLocked promotes the ELECTING node with the newest data; the other
// candidates and any waiting SYNCING nodes then copy the shard from it. SYNCING
// nodes are not candidates: their copy may be incomplete.
func (r *Registry) closeElectionLocked(shard int) {
	r.elections[shard] = time.Time{}
	var winner *Node
	for _, n := range r.sortedLocked() {
		if n.Shard == shard && n.State == StateElecting &&
			(winner == nil || n.MaxUpdatedAt.After(winner.MaxUpdatedAt)) {
			winner = n
		}
	}
	if winner == nil {
		return
	}
	winner.Role, winner.State = RolePrimary, StateActive
	r.cfg.Log.Info("election closed: PRIMARY elected", "shard", shard, "node", winner.ID, "max_updated_at", winner.MaxUpdatedAt)
	for _, n := range r.sortedLocked() {
		if n.Shard == shard && n != winner && (n.State == StateElecting || n.State == StateSyncing) {
			n.State = StateSyncing
			r.queueSyncLocked(n)
		}
	}
}

func (r *Registry) queueSyncLocked(n *Node) {
	select {
	case r.syncTasks <- SyncTask{NodeID: n.ID, Epoch: n.Epoch}:
	default:
		r.cfg.Log.Error("sync queue full; node stays SYNCING until it re-registers", "node", n.ID)
	}
}

// sortedLocked returns the nodes ordered by ID, so every decision is deterministic.
func (r *Registry) sortedLocked() []*Node {
	out := make([]*Node, 0, len(r.nodes))
	for _, n := range r.nodes {
		out = append(out, n)
	}
	slices.SortFunc(out, func(a, b *Node) int { return cmp.Compare(a.ID, b.ID) })
	return out
}

func rolePriority(r Role) int {
	if r == RolePrimary {
		return 0
	}
	return 1
}
