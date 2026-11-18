package store

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"sync"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/routing"
)

// ShardedStore is the RecordStore over the sharded, replicated cluster.
//
//   - Routing: a key belongs to shard routing.ShardFor(key): requests by a hash of
//     their ID, resources by a hash of their geohash cell.
//   - Writes go to the shard's primary, the only node that evaluates expectedVersion,
//     then the primary's result is copied to every ACTIVE and SYNCING replica of the
//     shard before the write is acknowledged. A replica that fails the copy is marked
//     DOWN (it resyncs when it comes back), so every ACTIVE replica always holds every
//     acknowledged write and can be promoted without losing any.
//   - A write is acknowledged only if its primary is still the primary afterwards, and
//     a shard never has writes in flight on two nodes (writeGate). Together these stop
//     a write that a replaced primary accepted from being reported as a success.
//   - Reads go to the primary, falling back to an ACTIVE replica.
//   - Queries go in parallel to every shard, except resource queries limited to some
//     geohash cells, which only ask the shards owning those cells. Results are merged.
//
// A node that fails a call is reported to the registry at once, so a dead primary is
// replaced on the first failed request instead of after the heartbeat timeout.
type ShardedStore struct {
	reg  *registry.Registry
	pool *Pool
	log  *slog.Logger
	gate *writeGate
}

var _ RecordStore = (*ShardedStore)(nil)

// NewSharded creates a ShardedStore.
func NewSharded(reg *registry.Registry, pool *Pool, log *slog.Logger) *ShardedStore {
	return &ShardedStore{reg: reg, pool: pool, log: log, gate: newWriteGate(reg.Shards())}
}

func (s *ShardedStore) shardOf(key string) int { return routing.ShardFor(key, s.reg.Shards()) }

func (s *ShardedStore) Get(ctx context.Context, key string) (model.Record, error) {
	shard := s.shardOf(key)
	for _, n := range s.reg.Readable(shard) {
		rec, err := s.pool.For(n.URL).Get(ctx, key)
		if errors.Is(err, ErrUnavailable) && ctx.Err() == nil {
			s.reg.MarkDown(n.ID, n.Epoch, "read failed: "+err.Error())
			continue // try the next copy
		}
		return rec, err // found, or a definite answer such as ErrNotFound
	}
	return model.Record{}, fmt.Errorf("%w: no readable node for shard %d", ErrUnavailable, shard)
}

func (s *ShardedStore) Put(ctx context.Context, key string, req model.PutRequest) (model.Record, bool, error) {
	var created bool
	rec, err := s.write(ctx, s.shardOf(key), func(node *Forwarder) (model.Record, error) {
		rec, c, err := node.Put(ctx, key, req)
		created = c
		return rec, err
	})
	return rec, created, err
}

func (s *ShardedStore) Delete(ctx context.Context, key string, expectedVersion *int64) error {
	_, err := s.write(ctx, s.shardOf(key), func(node *Forwarder) (model.Record, error) {
		return node.Delete(ctx, key, expectedVersion) // the tombstone, replicated like a write
	})
	return err
}

// write runs op (one write) on the shard's primary, replicates the result, and
// acknowledges it only if that node is still the primary.
func (s *ShardedStore) write(ctx context.Context, shard int, op func(*Forwarder) (model.Record, error)) (model.Record, error) {
	p, err := s.primary(shard)
	if err != nil {
		return model.Record{}, err
	}
	leave, err := s.gate.enter(ctx, shard, p)
	if err != nil {
		return model.Record{}, fmt.Errorf("%w: waiting for writes on the previous primary of shard %d: %v", ErrUnavailable, shard, err)
	}
	defer leave()
	// Re-checked after entering the gate: the primary may have been replaced while we
	// waited, and a replaced node must never receive new writes.
	if !s.reg.IsPrimary(p.ID, p.Epoch) {
		return model.Record{}, fmt.Errorf("%w: primary of shard %d changed; retry", ErrUnavailable, shard)
	}

	rec, err := op(s.pool.For(p.URL))
	if err != nil {
		s.reportPrimaryFailure(ctx, p, err)
		// Not retried on another node: the write may have been applied before the
		// primary failed. The caller gets 503 and retries; until it is replicated and
		// acknowledged, a write that did land is undone when that node resyncs.
		return model.Record{}, err
	}
	s.replicate(ctx, shard, rec)

	// If the primary was replaced while this write was in flight, the new primary may
	// not have the write (it was no longer a replication target), and the old node's
	// copy will be undone when it resyncs. Acknowledging it could let a second claim
	// on the same version succeed on the new primary (double booking), so the outcome
	// is reported as unknown: 503, and the caller re-reads and retries.
	if !s.reg.IsPrimary(p.ID, p.Epoch) {
		s.log.Warn("primary replaced during write; not acknowledging it", "node", p.ID, "shard", shard, "key", rec.Key)
		return model.Record{}, fmt.Errorf("%w: primary %s of shard %d was replaced during the write; re-read and retry", ErrUnavailable, p.ID, shard)
	}
	return rec, nil
}

// Query sends q to one readable node of every shard it may concern (see
// TargetShards) and merges the results in key order. Each shard applies the limit
// itself, so the first `limit` keys overall are always among the merged results. A
// shard with no readable node fails the query (503) rather than silently returning a
// partial answer.
func (s *ShardedStore) Query(ctx context.Context, q model.QueryRequest) ([]model.Record, error) {
	shards := TargetShards(q, s.reg.Shards())
	results := make([][]model.Record, len(shards))
	errs := make([]error, len(shards))
	var wg sync.WaitGroup
	for i, shard := range shards {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i], errs[i] = s.queryShard(ctx, shard, q)
		}()
	}
	wg.Wait()
	if err := errors.Join(errs...); err != nil {
		return nil, err
	}

	merged := make([]model.Record, 0)
	for _, r := range results {
		merged = append(merged, r...)
	}
	slices.SortFunc(merged, func(a, b model.Record) int { return cmp.Compare(a.Key, b.Key) })
	if limit := q.EffectiveLimit(); len(merged) > limit {
		merged = merged[:limit]
	}
	return merged, nil
}

// TargetShards returns, in order, the shards that can hold records matching q.
// Resources are sharded by geohash cell, so a resource query limited to some cells
// (geoCells, or match.geoCell) needs only the shards owning those cells: a proximity
// search over a cell and its 8 neighbours touches at most 3 shards, often fewer.
// Every other query is scattered to all shards.
func TargetShards(q model.QueryRequest, shards int) []int {
	cells := q.GeoCells
	if c, ok := q.Match["geoCell"]; ok && len(cells) == 0 {
		cells = []string{c}
	}
	if q.Collection != routing.CollectionResource || len(cells) == 0 {
		all := make([]int, shards)
		for i := range all {
			all[i] = i
		}
		return all
	}
	seen := make([]bool, shards)
	var out []int
	for _, c := range cells {
		if sh := routing.ShardForCell(c, shards); !seen[sh] {
			seen[sh] = true
			out = append(out, sh)
		}
	}
	slices.Sort(out)
	return out
}

func (s *ShardedStore) queryShard(ctx context.Context, shard int, q model.QueryRequest) ([]model.Record, error) {
	for _, n := range s.reg.Readable(shard) {
		recs, err := s.pool.For(n.URL).Query(ctx, q)
		if errors.Is(err, ErrUnavailable) && ctx.Err() == nil {
			s.reg.MarkDown(n.ID, n.Epoch, "query failed: "+err.Error())
			continue
		}
		return recs, err
	}
	return nil, fmt.Errorf("%w: no readable node for shard %d", ErrUnavailable, shard)
}

// Ready reports whether every shard has a primary, i.e. can take reads and writes.
func (s *ShardedStore) Ready(context.Context) error {
	for shard := range s.reg.Shards() {
		if _, ok := s.reg.Primary(shard); !ok {
			return fmt.Errorf("%w: shard %d has no primary", ErrUnavailable, shard)
		}
	}
	return nil
}

func (s *ShardedStore) primary(shard int) (registry.Node, error) {
	p, ok := s.reg.Primary(shard)
	if !ok {
		return registry.Node{}, fmt.Errorf("%w: shard %d has no primary", ErrUnavailable, shard)
	}
	return p, nil
}

// reportPrimaryFailure takes the primary out of service if it could not be reached.
// 4xx answers (conflicts, not found) are normal outcomes, and a call cut short by our
// own caller going away says nothing about the node.
func (s *ShardedStore) reportPrimaryFailure(ctx context.Context, p registry.Node, err error) {
	if errors.Is(err, ErrUnavailable) && ctx.Err() == nil {
		s.reg.MarkDown(p.ID, p.Epoch, "write failed: "+err.Error())
	}
}

// replicate copies the primary's result to every ACTIVE/SYNCING replica in parallel
// and returns when all have answered. It runs detached from the caller's
// cancellation: once the primary has applied a write, stopping halfway would leave
// replicas behind.
func (s *ShardedStore) replicate(ctx context.Context, shard int, rec model.Record) {
	ctx = context.WithoutCancel(ctx)
	var wg sync.WaitGroup
	for _, n := range s.reg.ReplicaTargets(shard) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			applied, err := s.pool.For(n.URL).ApplyReplica(ctx, rec)
			switch {
			case err != nil:
				s.log.Warn("replica write failed", "node", n.ID, "shard", shard, "key", rec.Key, "err", err)
				s.reg.MarkDown(n.ID, n.Epoch, "replica write failed")
			case !applied:
				// The replica holds a newer version than the primary just wrote. That
				// should not happen while the write gate holds; log it so it is visible.
				s.log.Warn("replica already holds a newer version", "node", n.ID, "shard", shard, "key", rec.Key, "version", rec.Version)
			}
		}()
	}
	wg.Wait()
}

// TopologyNode is one node in the GET /internal/topology view.
type TopologyNode struct {
	registry.Node
	Records    *int64 `json:"records,omitempty"`    // live records; nil if the node did not answer
	Tombstones *int64 `json:"tombstones,omitempty"` // deleted records kept for replication
	Error      string `json:"error,omitempty"`
}

// TopologyShard is one shard in the topology view.
type TopologyShard struct {
	Shard   int            `json:"shard"`
	Primary string         `json:"primary,omitempty"` // empty: shard unavailable
	Nodes   []TopologyNode `json:"nodes"`
}

// Topology describes the cluster: every shard, its nodes with role and state, and
// how many records each holds. It is how the data distribution, and the cluster's
// behaviour before, during and after a node failure, are observed.
func (s *ShardedStore) Topology(ctx context.Context) []TopologyShard {
	nodes := s.reg.Snapshot()
	views := make([]TopologyNode, len(nodes))
	var wg sync.WaitGroup
	for i, n := range nodes {
		views[i].Node = n
		if n.State == registry.StateDown {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			sctx, cancel := context.WithTimeout(ctx, 2*time.Second)
			defer cancel()
			st, err := s.pool.For(n.URL).Stats(sctx)
			if err != nil {
				views[i].Error = err.Error()
				return
			}
			views[i].Records, views[i].Tombstones = &st.Records, &st.Tombstones
		}()
	}
	wg.Wait()

	shards := make([]TopologyShard, s.reg.Shards())
	for i := range shards {
		shards[i] = TopologyShard{Shard: i, Nodes: []TopologyNode{}}
		if p, ok := s.reg.Primary(i); ok {
			shards[i].Primary = p.ID
		}
	}
	for _, v := range views {
		shards[v.Shard].Nodes = append(shards[v.Shard].Nodes, v)
	}
	return shards
}
