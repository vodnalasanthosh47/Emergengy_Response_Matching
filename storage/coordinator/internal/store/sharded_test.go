package store_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/fakenode"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/routing"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

// Geohash cells (precision 6) around Bengaluru and elsewhere; they map to all 3 shards.
var cells = []string{"tdr1yz", "tdr1yy", "tdr1zn", "tdr1yv", "tdr1yw", "tdr1yx", "u4pruy", "te7udq", "ttnfv2", "tsq4e7"}

func newStore(t *testing.T) (*store.ShardedStore, *fakenode.Cluster) {
	c := fakenode.NewCluster(t, 3, 2)
	return store.NewSharded(c.Reg, store.NewPool(time.Second), c.Log), c
}

func requestKey(i int) string { return fmt.Sprintf("request:%08d-0000-4000-8000-%012d", i, i) }

func resourceKey(cell string, i int) string {
	return fmt.Sprintf("resource:%s:%08d-1111-4000-8000-%012d", cell, i, i)
}

// putRequest stores a PENDING request under request:{id}. The geoCell index is only
// searchable data here; it plays no part in where the request is stored.
func putRequest(t *testing.T, s *store.ShardedStore, key, cell string) model.Record {
	t.Helper()
	return mustPut(t, s, key, map[string]string{"collection": "request", "geoCell": cell, "status": "PENDING", "type": "WATER"})
}

func putResource(t *testing.T, s *store.ShardedStore, key string) model.Record {
	t.Helper()
	k, err := routing.ParseKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return mustPut(t, s, key, map[string]string{"collection": "resource", "geoCell": k.GeoCell, "status": "AVAILABLE", "type": "WATER"})
}

func mustPut(t *testing.T, s *store.ShardedStore, key string, indexes map[string]string) model.Record {
	t.Helper()
	rec, _, err := s.Put(context.Background(), key, model.PutRequest{Value: json.RawMessage(`{"k":"` + key + `"}`), Indexes: indexes})
	if err != nil {
		t.Fatalf("Put %s: %v", key, err)
	}
	return rec
}

// TestRecordsLiveOnTheirShardOnPrimaryAndReplica: requests are placed by request ID,
// resources by geohash cell; every record is on exactly one shard, on both its nodes.
func TestRecordsLiveOnTheirShardOnPrimaryAndReplica(t *testing.T) {
	s, c := newStore(t)
	perShard := make([]int, 3)
	for i := range 60 {
		key := requestKey(i)
		putRequest(t, s, key, "tdr1yz") // all from the same place...
		perShard[routing.ShardFor(key, 3)]++
	}
	requestShards := slices.Clone(perShard)
	for i := range 60 {
		key := resourceKey(cells[i%len(cells)], i)
		putResource(t, s, key)
		perShard[routing.ShardForCell(cells[i%len(cells)], 3)]++
	}
	for shard, n := range requestShards {
		if n == 0 { // ...yet spread over every shard, because they are placed by ID
			t.Errorf("no request landed on shard %d (%v): requests must be sharded by ID, not location", shard, requestShards)
		}
	}
	for shard, nodes := range c.Shards {
		for _, n := range nodes {
			recs := n.Records()
			if len(recs) != perShard[shard] {
				t.Errorf("%s holds %d records, want %d", n.ID, len(recs), perShard[shard])
			}
			for _, r := range recs {
				if got := routing.ShardFor(r.Key, 3); got != shard {
					t.Errorf("%s holds %s, which belongs to shard %d", n.ID, r.Key, got)
				}
			}
		}
		// Primary and replica hold identical copies (same versions and timestamps).
		p, r := nodes[0].Records(), nodes[1].Records()
		for i := range p {
			if p[i].Key != r[i].Key || p[i].Version != r[i].Version || !p[i].UpdatedAt.Equal(r[i].UpdatedAt) {
				t.Errorf("shard %d copies differ: %+v vs %+v", shard, p[i], r[i])
			}
		}
	}
}

func TestResourcesOfACellShareOneShard(t *testing.T) {
	s, c := newStore(t)
	const cell = "tdr1yz"
	for i := range 10 {
		putResource(t, s, resourceKey(cell, i))
	}
	want := routing.ShardForCell(cell, 3)
	for shard, nodes := range c.Shards {
		got := nodes[0].Live()
		if shard == want && got != 10 || shard != want && got != 0 {
			t.Errorf("shard %d holds %d of the cell's resources; all 10 belong on shard %d", shard, got, want)
		}
	}
}

func TestTargetShards(t *testing.T) {
	shardOf := func(cs ...string) []int {
		var out []int
		for _, c := range cs {
			out = append(out, routing.ShardForCell(c, 3))
		}
		slices.Sort(out)
		return slices.Compact(out)
	}
	tests := []struct {
		name string
		q    model.QueryRequest
		want []int
	}{
		{"resource in cells: owners only", model.QueryRequest{Collection: "resource", GeoCells: []string{"tdr1yz", "tdr1yy"}}, shardOf("tdr1yz", "tdr1yy")},
		{"resource in one cell via match", model.QueryRequest{Collection: "resource", Match: map[string]string{"geoCell": "tdr1yz"}}, shardOf("tdr1yz")},
		{"resource, no cells: all", model.QueryRequest{Collection: "resource", Match: map[string]string{"type": "WATER"}}, []int{0, 1, 2}},
		{"requests are sharded by ID: always all", model.QueryRequest{Collection: "request", GeoCells: []string{"tdr1yz"}}, []int{0, 1, 2}},
		{"many cells cover all shards at most once", model.QueryRequest{Collection: "resource", GeoCells: cells}, []int{0, 1, 2}},
	}
	for _, tt := range tests {
		if got := store.TargetShards(tt.q, 3); !slices.Equal(got, tt.want) {
			t.Errorf("%s: TargetShards = %v, want %v", tt.name, got, tt.want)
		}
	}
}

// TestResourceQueryOnlyAsksOwningShards: a proximity search touches only the shards
// that own the queried cells, so it keeps working while an unrelated shard is down.
func TestResourceQueryOnlyAsksOwningShards(t *testing.T) {
	s, c := newStore(t)
	for i, cell := range cells {
		putResource(t, s, resourceKey(cell, i))
	}
	const cell = "tdr1yz" // shard 2
	owner := routing.ShardForCell(cell, 3)
	q := model.QueryRequest{Collection: "resource", GeoCells: []string{cell}, Match: map[string]string{"type": "WATER"}}

	recs, err := s.Query(context.Background(), q)
	if err != nil || len(recs) != 1 || recs[0].Key != resourceKey(cell, 0) {
		t.Fatalf("Query = %v, %v; want the one resource in %s", recs, err, cell)
	}
	for shard, nodes := range c.Shards {
		asked := nodes[0].Queries.Load() + nodes[1].Queries.Load()
		if shard == owner && asked != 1 || shard != owner && asked != 0 {
			t.Errorf("shard %d was asked %d times; only shard %d owns %s", shard, asked, owner, cell)
		}
	}

	// Take a shard that does not own the cell completely down.
	other := (owner + 1) % 3
	for _, n := range c.Shards[other] {
		n.Down.Store(true)
	}
	if recs, err := s.Query(context.Background(), q); err != nil || len(recs) != 1 {
		t.Fatalf("geo query with unrelated shard %d down = %v, %v", other, recs, err)
	}
	// A query that must scatter cannot give a complete answer, and says so.
	if _, err := s.Query(context.Background(), model.QueryRequest{Collection: "request"}); !errors.Is(err, store.ErrUnavailable) {
		t.Fatalf("scatter query with shard %d down = %v, want ErrUnavailable", other, err)
	}
}

func TestReadAndWriteContinueWhenPrimaryFails(t *testing.T) {
	s, c := newStore(t)
	ctx := context.Background()
	key := requestKey(1)
	shard := routing.ShardFor(key, 3)
	putRequest(t, s, key, "tdr1yz")

	primary, replica := c.Shards[shard][0], c.Shards[shard][1]
	primary.Down.Store(true)

	// The read fails on the primary, which is marked DOWN; the replica answers and is
	// promoted at once (no need to wait for the heartbeat timeout).
	got, err := s.Get(ctx, key)
	if err != nil || got.Key != key {
		t.Fatalf("Get during failure = %+v, %v", got, err)
	}
	if p, ok := c.Reg.Primary(shard); !ok || p.ID != replica.ID {
		t.Fatalf("primary = %+v, %v; want %s promoted", p, ok, replica.ID)
	}
	// Writes keep working on the new primary.
	rec, _, err := s.Put(ctx, key, model.PutRequest{Value: json.RawMessage(`{}`),
		Indexes: map[string]string{"collection": "request"}, ExpectedVersion: &got.Version})
	if err != nil || rec.Version != got.Version+1 {
		t.Fatalf("CAS write after failover = %+v, %v", rec, err)
	}
	if err := s.Ready(ctx); err != nil {
		t.Errorf("Ready after failover = %v", err)
	}
}

func TestWriteToFailedPrimaryReturnsUnavailableAndFailsOver(t *testing.T) {
	s, c := newStore(t)
	key := resourceKey("tdr1yy", 2)
	shard := routing.ShardFor(key, 3)
	c.Shards[shard][0].Down.Store(true)

	_, _, err := s.Put(context.Background(), key, model.PutRequest{Value: json.RawMessage(`{}`),
		Indexes: map[string]string{"collection": "resource", "geoCell": "tdr1yy"}})
	if !errors.Is(err, store.ErrUnavailable) {
		t.Fatalf("Put to dead primary = %v, want ErrUnavailable", err)
	}
	// The client's retry succeeds on the promoted replica.
	putResource(t, s, key)
	if got := c.Shards[shard][1].Live(); got != 1 {
		t.Errorf("new primary holds %d records", got)
	}
}

func TestFailedReplicaIsMarkedDownWriteStillSucceeds(t *testing.T) {
	s, c := newStore(t)
	key := requestKey(3)
	shard := routing.ShardFor(key, 3)
	replica := c.Shards[shard][1]
	replica.Down.Store(true)

	putRequest(t, s, key, "tdr1yz")
	n, _ := c.Reg.Get(replica.ID)
	if n.State != registry.StateDown {
		t.Fatalf("replica state = %s, want DOWN", n.State)
	}
	if got := c.Reg.ReplicaTargets(shard); len(got) != 0 {
		t.Errorf("DOWN replica still receives writes: %v", got)
	}
}

func TestDeleteReplicatesTombstone(t *testing.T) {
	s, c := newStore(t)
	key := resourceKey("tdr1zn", 4)
	shard := routing.ShardFor(key, 3)
	putResource(t, s, key)
	if err := s.Delete(context.Background(), key, nil); err != nil {
		t.Fatal(err)
	}
	for _, n := range c.Shards[shard] {
		recs := n.Records()
		if len(recs) != 1 || !recs[0].Deleted || recs[0].Version != 2 {
			t.Errorf("%s = %+v, want a version-2 tombstone", n.ID, recs)
		}
	}
	if _, err := s.Get(context.Background(), key); !errors.Is(err, store.ErrNotFound) {
		t.Errorf("Get deleted = %v", err)
	}
}

func TestQueryMergesAllShardsInKeyOrder(t *testing.T) {
	s, _ := newStore(t)
	for i := range 30 {
		putRequest(t, s, requestKey(i), cells[i%len(cells)])
	}
	recs, err := s.Query(context.Background(), model.QueryRequest{Collection: "request", Match: map[string]string{"status": "PENDING"}, Limit: 1000})
	if err != nil || len(recs) != 30 {
		t.Fatalf("Query = %d records, %v; want 30", len(recs), err)
	}
	for i := 1; i < len(recs); i++ {
		if recs[i-1].Key >= recs[i].Key {
			t.Fatalf("not sorted at %d: %s, %s", i, recs[i-1].Key, recs[i].Key)
		}
	}
	limited, err := s.Query(context.Background(), model.QueryRequest{Collection: "request", Limit: 7})
	if err != nil || len(limited) != 7 || limited[6].Key != recs[6].Key {
		t.Fatalf("limited query = %d records, %v", len(limited), err)
	}
}

func TestQueryAndReadyFailWhenAShardHasNoLiveNode(t *testing.T) {
	s, c := newStore(t)
	for _, n := range c.Shards[1] {
		n.Down.Store(true)
	}
	if _, err := s.Query(context.Background(), model.QueryRequest{Collection: "request"}); !errors.Is(err, store.ErrUnavailable) {
		t.Fatalf("Query = %v, want ErrUnavailable", err)
	}
	if err := s.Ready(context.Background()); !errors.Is(err, store.ErrUnavailable) {
		t.Fatalf("Ready = %v, want ErrUnavailable", err)
	}
}

func TestTopologyShowsRolesAndCounts(t *testing.T) {
	s, c := newStore(t)
	key := requestKey(42)
	putRequest(t, s, key, "tdr1yz")
	shard := routing.ShardFor(key, 3)
	topo := s.Topology(context.Background())
	if len(topo) != 3 {
		t.Fatalf("topology has %d shards", len(topo))
	}
	view := topo[shard]
	if view.Primary != c.Shards[shard][0].ID || len(view.Nodes) != 2 {
		t.Fatalf("shard view = %+v", view)
	}
	for _, n := range view.Nodes {
		if n.Records == nil || *n.Records != 1 {
			t.Errorf("%s records = %v", n.ID, n.Records)
		}
	}
}
