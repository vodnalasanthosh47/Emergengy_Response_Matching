package replication

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/fakenode"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/routing"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

// keysOn returns n keys that route to shard.
func keysOn(shard, n int) []string {
	var out []string
	for i := 0; len(out) < n; i++ {
		k := fmt.Sprintf("request:c%04d:%d", i, i)
		if routing.ShardFor(k, 3) == shard {
			out = append(out, k)
		}
	}
	return out
}

func write(t *testing.T, s *store.ShardedStore, key, v string) {
	t.Helper()
	if _, _, err := s.Put(context.Background(), key, model.PutRequest{
		Value: json.RawMessage(`{"v":"` + v + `"}`), Indexes: map[string]string{"collection": "request"}}); err != nil {
		t.Fatalf("Put %s: %v", key, err)
	}
}

func assertIdentical(t *testing.T, a, b *fakenode.Node) {
	t.Helper()
	ra, rb := a.Records(), b.Records()
	if len(ra) != len(rb) {
		t.Fatalf("%s has %d records, %s has %d", a.ID, len(ra), b.ID, len(rb))
	}
	for i := range ra {
		x, y := ra[i], rb[i]
		if x.Key != y.Key || x.Version != y.Version || x.Deleted != y.Deleted || !x.UpdatedAt.Equal(y.UpdatedAt) || string(x.Value) != string(y.Value) {
			t.Fatalf("diverged at %d:\n %s: %+v\n %s: %+v", i, a.ID, x, b.ID, y)
		}
	}
}

// TestFailedNodeRecoversMissedWritesDeletesAndDivergence walks through a full
// failure: the shard's primary dies, writes and deletes continue on the promoted
// replica, and the old primary comes back holding a write it never replicated.
func TestFailedNodeRecoversMissedWritesDeletesAndDivergence(t *testing.T) {
	c := fakenode.NewCluster(t, 3, 2)
	pool := store.NewPool(time.Second)
	s := store.NewSharded(c.Reg, pool, c.Log)
	syncer := &Syncer{Reg: c.Reg, Pool: pool, Overlap: 30 * time.Second, PageSize: 3, Retry: time.Millisecond, Log: c.Log}

	shard := 0
	a, b := c.Shards[shard][0], c.Shards[shard][1]
	keys := keysOn(shard, 12)
	for _, k := range keys[:8] {
		write(t, s, k, "before")
	}

	// a (primary) accepted one more write but crashed before replicating it.
	// keys[1] is not written again during the outage, so only the repair pass can fix it.
	var orphan model.Record
	for _, r := range a.Records() {
		if r.Key == keys[1] {
			orphan = r
		}
	}
	orphan.Version++
	orphan.UpdatedAt = orphan.UpdatedAt.Add(time.Second)
	orphan.Value = json.RawMessage(`{"v":"never-acknowledged"}`)
	a.Seed(orphan)
	a.Seed(model.Record{Key: "request:zzzz:orphan-only-on-a", Value: json.RawMessage(`{}`), Version: 1, UpdatedAt: orphan.UpdatedAt})
	a.Down.Store(true)

	// a stops heartbeating; the others keep going. The failure detector marks a DOWN
	// after HEARTBEAT_TIMEOUT and promotes b.
	for range 4 {
		c.Clock.Advance(5 * time.Second)
		for s, nodes := range c.Shards {
			for _, n := range nodes {
				if n != a {
					_, _ = c.Reg.Heartbeat(c.Info(n, s))
				}
			}
		}
		c.Reg.Sweep()
	}

	// During the outage: updates, new keys and a delete all land on b.
	for _, k := range keys[2:12] {
		write(t, s, k, "during")
	}
	if err := s.Delete(context.Background(), keys[3], nil); err != nil {
		t.Fatal(err)
	}
	if p, _ := c.Reg.Primary(shard); p.ID != b.ID {
		t.Fatalf("primary = %s, want %s", p.ID, b.ID)
	}

	// a restarts and registers; it rejoins as a SYNCING replica.
	a.Down.Store(false)
	if _, err := c.Reg.Register(c.Info(a, shard)); err != nil {
		t.Fatal(err)
	}
	tasks := c.DrainSyncTasks()
	if len(tasks) != 1 || tasks[0].NodeID != a.ID {
		t.Fatalf("tasks = %v", tasks)
	}
	if got := c.Reg.Readable(shard); len(got) != 1 || got[0].ID != b.ID {
		t.Fatalf("Readable during sync = %v; a SYNCING node must not serve reads", got)
	}

	// A write that arrives while a is syncing reaches a too.
	write(t, s, keys[0], "while-syncing")

	res, err := syncer.SyncOnce(context.Background(), tasks[0])
	if err != nil {
		t.Fatalf("SyncOnce: %v", err)
	}
	assertIdentical(t, a, b)
	if res.Purged != 1 || res.Repaired < 1 {
		t.Errorf("result = %+v, want the orphan purged and the divergent write repaired", res)
	}
	if n, _ := c.Reg.Get(a.ID); n.State != registry.StateActive || n.Role != registry.RoleReplica {
		t.Fatalf("a after sync = %+v, want ACTIVE replica", n)
	}
	// Now both copies serve the shard again, and a failure of b is survivable.
	if got := c.Reg.Readable(shard); len(got) != 2 {
		t.Fatalf("Readable after sync = %v", got)
	}
}

func TestEmptyNodeGetsAFullCopyAcrossPages(t *testing.T) {
	c := fakenode.NewCluster(t, 3, 2)
	pool := store.NewPool(time.Second)
	s := store.NewSharded(c.Reg, pool, c.Log)
	syncer := &Syncer{Reg: c.Reg, Pool: pool, Overlap: 30 * time.Second, PageSize: 4, Retry: time.Millisecond, Log: c.Log}

	for _, k := range keysOn(2, 25) {
		write(t, s, k, "x")
	}
	fresh := fakenode.Start(t, "storage-2-9")
	if _, err := c.Reg.Register(c.Info(fresh, 2)); err != nil {
		t.Fatal(err)
	}
	if _, err := syncer.SyncOnce(context.Background(), c.DrainSyncTasks()[0]); err != nil {
		t.Fatal(err)
	}
	assertIdentical(t, fresh, c.Shards[2][0])
	if fresh.Live() != 25 {
		t.Errorf("fresh node has %d records", fresh.Live())
	}
}

func TestSyncIsRetriedUntilPrimaryIsReachable(t *testing.T) {
	c := fakenode.NewCluster(t, 3, 2)
	pool := store.NewPool(200 * time.Millisecond)
	s := store.NewSharded(c.Reg, pool, c.Log)
	for _, k := range keysOn(1, 5) {
		write(t, s, k, "x")
	}
	a, b := c.Shards[1][0], c.Shards[1][1]
	c.Reg.MarkDown(b.ID, mustNode(t, c.Reg, b.ID).Epoch, "test")
	if _, err := c.Reg.Register(c.Info(b, 1)); err != nil {
		t.Fatal(err)
	}

	a.Down.Store(true) // the sync source is briefly unreachable
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	syncer := &Syncer{Reg: c.Reg, Pool: pool, Overlap: 30 * time.Second, PageSize: 10, Retry: 20 * time.Millisecond, Log: c.Log}
	go syncer.Run(ctx)
	time.Sleep(100 * time.Millisecond)
	if n := mustNode(t, c.Reg, b.ID); n.State != registry.StateSyncing {
		t.Fatalf("b = %s while the source is down", n.State)
	}
	a.Down.Store(false)
	deadline := time.Now().Add(2 * time.Second)
	for mustNode(t, c.Reg, b.ID).State != registry.StateActive {
		if time.Now().After(deadline) {
			t.Fatal("sync never completed after the primary came back")
		}
		time.Sleep(10 * time.Millisecond)
	}
	assertIdentical(t, a, b)
}

func mustNode(t *testing.T, r *registry.Registry, id string) registry.Node {
	t.Helper()
	n, ok := r.Get(id)
	if !ok {
		t.Fatalf("no node %s", id)
	}
	return n
}
