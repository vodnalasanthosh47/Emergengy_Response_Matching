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
//   - Routing: a key belongs to shard routing.ShardFor(key) (geohash of its homeCell).
//   - Writes go to the shard's primary, the only node that evaluates expectedVersion,
//     then the primary's result is copied to every ACTIVE and SYNCING replica of the
//     shard before the write is acknowledged. A replica that fails the copy is marked
//     DOWN (it resyncs when it comes back), so every ACTIVE replica always holds every
//     acknowledged write and can be promoted without losing any.
//   - Reads go to the primary, falling back to an ACTIVE replica.
//   - Queries go to every shard in parallel and are merged.
//
// A node that fails a call is reported to the registry at once, so a dead primary is
// replaced on the first failed request instead of after the heartbeat timeout.
type ShardedStore struct {
	reg  *registry.Registry
	pool *Pool
	log  *slog.Logger
}

var _ RecordStore = (*ShardedStore)(nil)

// NewSharded creates a ShardedStore.
func NewSharded(reg *registry.Registry, pool *Pool, log *slog.Logger) *ShardedStore {
	return &ShardedStore{reg: reg, pool: pool, log: log}
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
	shard := s.shardOf(key)
	p, err := s.primary(shard)
	if err != nil {
		return model.Record{}, false, err
	}
	rec, created, err := s.pool.For(p.URL).Put(ctx, key, req)
	if err != nil {
		s.reportPrimaryFailure(ctx, p, err)
		// Not retried on another node: the write may have been applied before the
		// primary failed. The caller gets 503 and retries; until it is replicated and
		// acknowledged, a write that did land is undone when that node resyncs.
		return rec, created, err
	}
	s.replicate(ctx, shard, rec)
	return rec, created, nil
}

func (s *ShardedStore) Delete(ctx context.Context, key string, expectedVersion *int64) error {
	shard := s.shardOf(key)
	p, err := s.primary(shard)
	if err != nil {
		return err
	}
	tomb, err := s.pool.For(p.URL).Delete(ctx, key, expectedVersion)
	if err != nil {
		s.reportPrimaryFailure(ctx, p, err)
		return err
	}
	s.replicate(ctx, shard, tomb)
	return nil
}

// Query sends q to one readable node of every shard and merges the results in key
// order. Each shard applies the limit itself, so the first `limit` keys overall are
// always among the merged results. A shard with no readable node fails the query
// (503) rather than silently returning a partial answer.
func (s *ShardedStore) Query(ctx context.Context, q model.QueryRequest) ([]model.Record, error) {
	shards := s.reg.Shards()
	results := make([][]model.Record, shards)
	errs := make([]error, shards)
	var wg sync.WaitGroup
	for shard := range shards {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[shard], errs[shard] = s.queryShard(ctx, shard, q)
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
			if _, err := s.pool.For(n.URL).ApplyReplica(ctx, rec); err != nil {
				s.log.Warn("replica write failed", "node", n.ID, "shard", shard, "key", rec.Key, "err", err)
				s.reg.MarkDown(n.ID, n.Epoch, "replica write failed")
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
