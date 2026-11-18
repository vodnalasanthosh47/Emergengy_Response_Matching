package store_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/fakenode"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/routing"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

var cells = []string{"tdr1x", "tdr1y", "tdr1z", "tdr1v", "tdr1w", "u4pru", "te7ud", "ttnfv", "tsq4e", "tepf1"}

func newStore(t *testing.T) (*store.ShardedStore, *fakenode.Cluster) {
	c := fakenode.NewCluster(t, 3, 2)
	return store.NewSharded(c.Reg, store.NewPool(time.Second), c.Log), c
}

func put(t *testing.T, s *store.ShardedStore, key, cell string) model.Record {
	t.Helper()
	rec, _, err := s.Put(context.Background(), key, model.PutRequest{
		Value:   json.RawMessage(`{"k":"` + key + `"}`),
		Indexes: map[string]string{"collection": "request", "geoCell": cell, "status": "PENDING"},
	})
	if err != nil {
		t.Fatalf("Put %s: %v", key, err)
	}
	return rec
}

func TestKeysLiveOnTheirGeohashShardOnPrimaryAndReplica(t *testing.T) {
	s, c := newStore(t)
	perShard := make([]int, 3)
	for i := 0; i < 90; i++ {
		cell := cells[i%len(cells)]
		key := fmt.Sprintf("request:%s:%03d", cell, i)
		put(t, s, key, cell)
		perShard[routing.ShardFor(key, 3)]++
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
	for shard, n := range perShard {
		if n == 0 {
			t.Errorf("shard %d got no data: %v", shard, perShard)
		}
	}
}

func TestReadAndWriteContinueWhenPrimaryFails(t *testing.T) {
	s, c := newStore(t)
	ctx := context.Background()
	key := "request:tdr1y:k1"
	shard := routing.ShardFor(key, 3)
	put(t, s, key, "tdr1y")

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
	key := "request:tdr1x:k2"
	shard := routing.ShardFor(key, 3)
	c.Shards[shard][0].Down.Store(true)

	_, _, err := s.Put(context.Background(), key, model.PutRequest{Value: json.RawMessage(`{}`), Indexes: map[string]string{"collection": "request"}})
	if !errors.Is(err, store.ErrUnavailable) {
		t.Fatalf("Put to dead primary = %v, want ErrUnavailable", err)
	}
	// The client's retry succeeds on the promoted replica.
	put(t, s, key, "tdr1x")
	if got := c.Shards[shard][1].Live(); got != 1 {
		t.Errorf("new primary holds %d records", got)
	}
}

func TestFailedReplicaIsMarkedDownWriteStillSucceeds(t *testing.T) {
	s, c := newStore(t)
	key := "request:tdr1z:k3"
	shard := routing.ShardFor(key, 3)
	replica := c.Shards[shard][1]
	replica.Down.Store(true)

	put(t, s, key, "tdr1z")
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
	key := "request:tdr1v:k4"
	shard := routing.ShardFor(key, 3)
	put(t, s, key, "tdr1v")
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
	for i := 0; i < 30; i++ {
		cell := cells[i%len(cells)]
		put(t, s, fmt.Sprintf("request:%s:%03d", cell, i), cell)
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
	put(t, s, "request:tdr1y:t1", "tdr1y")
	shard := routing.ShardFor("request:tdr1y:t1", 3)
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
