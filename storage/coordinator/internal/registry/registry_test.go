package registry

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time          { return c.t }
func (c *clock) advance(d time.Duration) { c.t = c.t.Add(d) }
func info(id string, shard int, max time.Time) model.NodeInfo {
	return model.NodeInfo{NodeID: id, ShardID: shard, URL: "http://" + id + ":8080", MaxUpdatedAt: max}
}

var base = time.Date(2026, 11, 17, 10, 0, 0, 0, time.UTC)

func newReg() (*Registry, *clock) {
	c := &clock{t: base}
	return New(Config{Shards: 3, HeartbeatTimeout: 15 * time.Second, ElectionWindow: 10 * time.Second,
		Now: c.now, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}), c
}

func drain(r *Registry) []SyncTask {
	var out []SyncTask
	for {
		select {
		case t := <-r.SyncTasks():
			out = append(out, t)
		default:
			return out
		}
	}
}

// bootShard registers a and b on shard 0 and closes the election; a has newer data.
func bootShard(t *testing.T, r *Registry, c *clock) {
	t.Helper()
	mustRegister(t, r, info("a", 0, base))
	mustRegister(t, r, info("b", 0, base.Add(-time.Minute)))
	if _, ok := r.Primary(0); ok {
		t.Fatal("primary chosen before the election window closed")
	}
	c.advance(10 * time.Second)
	r.Sweep()
}

func mustRegister(t *testing.T, r *Registry, i model.NodeInfo) {
	t.Helper()
	if _, err := r.Register(i); err != nil {
		t.Fatal(err)
	}
}

func TestElectionPicksNewestDataAndSyncsTheRest(t *testing.T) {
	r, c := newReg()
	bootShard(t, r, c)

	p, ok := r.Primary(0)
	if !ok || p.ID != "a" {
		t.Fatalf("primary = %+v, %v; want a (newest data)", p, ok)
	}
	b, _ := r.Get("b")
	if b.State != StateSyncing || b.Role != RoleReplica {
		t.Fatalf("b = %+v, want SYNCING replica", b)
	}
	tasks := drain(r)
	if len(tasks) != 1 || tasks[0].NodeID != "b" {
		t.Fatalf("sync tasks = %v", tasks)
	}
	if got := r.Readable(0); len(got) != 1 || got[0].ID != "a" {
		t.Errorf("Readable = %v; a SYNCING node must not serve reads", got)
	}
	if got := r.ReplicaTargets(0); len(got) != 1 || got[0].ID != "b" {
		t.Errorf("ReplicaTargets = %v; a SYNCING node must receive writes", got)
	}
	if !r.SetActive(tasks[0]) {
		t.Fatal("SetActive failed")
	}
	if got := r.Readable(0); len(got) != 2 || got[0].ID != "a" || got[1].ID != "b" {
		t.Errorf("Readable = %v, want [a b]", got)
	}
}

func TestMissedHeartbeatsFailOverToReplica(t *testing.T) {
	r, c := newReg()
	bootShard(t, r, c)
	r.SetActive(drain(r)[0])

	// b keeps heartbeating, a goes silent.
	for i := 0; i < 4; i++ {
		c.advance(5 * time.Second)
		if _, err := r.Heartbeat(info("b", 0, base)); err != nil {
			t.Fatal(err)
		}
		r.Sweep()
	}
	a, _ := r.Get("a")
	if a.State != StateDown {
		t.Fatalf("a = %+v, want DOWN after 20s of silence", a)
	}
	if p, ok := r.Primary(0); !ok || p.ID != "b" {
		t.Fatalf("primary = %+v, %v; want b promoted", p, ok)
	}
	if r.Failovers() != 1 {
		t.Errorf("Failovers = %d", r.Failovers())
	}
	if got := r.Readable(0); len(got) != 1 || got[0].ID != "b" {
		t.Errorf("Readable = %v; DOWN nodes must not be routed to", got)
	}

	// a's late heartbeat is refused; it must register again and comes back as a
	// SYNCING replica (never straight back to primary).
	if _, err := r.Heartbeat(info("a", 0, base)); !errors.Is(err, ErrUnknownNode) {
		t.Fatalf("heartbeat from DOWN node = %v", err)
	}
	mustRegister(t, r, info("a", 0, base))
	a, _ = r.Get("a")
	if a.State != StateSyncing || a.Role != RoleReplica {
		t.Fatalf("rejoined a = %+v", a)
	}
	if tasks := drain(r); len(tasks) != 1 || tasks[0].NodeID != "a" {
		t.Fatalf("sync tasks = %v", tasks)
	}
}

func TestMarkDownIgnoresStaleEpoch(t *testing.T) {
	r, c := newReg()
	bootShard(t, r, c)
	task := drain(r)[0]
	r.SetActive(task)
	old, _ := r.Get("b")

	mustRegister(t, r, info("b", 0, base)) // b restarted: new epoch
	r.MarkDown("b", old.Epoch, "stale failure report")
	if b, _ := r.Get("b"); b.State != StateSyncing {
		t.Fatalf("stale MarkDown affected the new registration: %+v", b)
	}
	if r.SetActive(task) {
		t.Fatal("SetActive with a stale task succeeded")
	}
}

func TestPrimaryReRegisteringHandsOverToReplica(t *testing.T) {
	r, c := newReg()
	bootShard(t, r, c)
	r.SetActive(drain(r)[0])

	// a restarts before the failure detector noticed and registers again.
	mustRegister(t, r, info("a", 0, base))
	if p, ok := r.Primary(0); !ok || p.ID != "b" {
		t.Fatalf("primary = %+v, %v; want b", p, ok)
	}
	if a, _ := r.Get("a"); a.State != StateSyncing {
		t.Fatalf("a = %+v", a)
	}
}

func TestShardWithoutActiveReplicaWaitsForElection(t *testing.T) {
	r, c := newReg()
	bootShard(t, r, c) // b is SYNCING (never finished)
	drain(r)

	r.MarkDown("a", mustGet(t, r, "a").Epoch, "crash")
	if _, ok := r.Primary(0); ok {
		t.Fatal("an incomplete SYNCING node must not be promoted")
	}
	// a comes back: no primary, so an election runs and a (the only candidate with
	// complete data) wins; b resyncs from it.
	mustRegister(t, r, info("a", 0, base))
	if _, err := r.Heartbeat(info("b", 0, base)); err != nil { // b is alive, just incomplete
		t.Fatal(err)
	}
	c.advance(10 * time.Second)
	r.Sweep()
	if p, ok := r.Primary(0); !ok || p.ID != "a" {
		t.Fatalf("primary = %+v, %v", p, ok)
	}
	if tasks := drain(r); len(tasks) != 1 || tasks[0].NodeID != "b" {
		t.Fatalf("sync tasks = %v", tasks)
	}
}

func TestRegisterValidation(t *testing.T) {
	r, _ := newReg()
	for _, bad := range []model.NodeInfo{
		{NodeID: "", ShardID: 0, URL: "http://x"},
		{NodeID: "x", ShardID: 3, URL: "http://x"},
		{NodeID: "x", ShardID: -1, URL: "http://x"},
		{NodeID: "x", ShardID: 0, URL: ""},
	} {
		if _, err := r.Register(bad); !errors.Is(err, ErrInvalidNode) {
			t.Errorf("Register(%+v) = %v", bad, err)
		}
	}
	if _, err := r.Heartbeat(info("ghost", 0, base)); !errors.Is(err, ErrUnknownNode) {
		t.Errorf("heartbeat from unknown node = %v", err)
	}
}

func mustGet(t *testing.T, r *Registry, id string) Node {
	t.Helper()
	n, ok := r.Get(id)
	if !ok {
		t.Fatalf("no node %s", id)
	}
	return n
}

func TestIsPrimary(t *testing.T) {
	r, c := newReg()
	bootShard(t, r, c)
	r.SetActive(drain(r)[0])
	a := mustGet(t, r, "a")
	if !r.IsPrimary("a", a.Epoch) {
		t.Fatal("a should be primary")
	}
	if r.IsPrimary("b", mustGet(t, r, "b").Epoch) || r.IsPrimary("ghost", 1) {
		t.Fatal("only the shard's primary is primary")
	}
	if r.IsPrimary("a", a.Epoch+100) {
		t.Fatal("a stale or wrong epoch must not count")
	}
	r.MarkDown("a", a.Epoch, "crash")
	if r.IsPrimary("a", a.Epoch) {
		t.Fatal("a DOWN node is not primary")
	}
	if !r.IsPrimary("b", mustGet(t, r, "b").Epoch) {
		t.Fatal("b should have been promoted")
	}
	// a comes back with a new epoch as a SYNCING replica: its old epoch is not primary.
	mustRegister(t, r, info("a", 0, base))
	if r.IsPrimary("a", a.Epoch) || r.IsPrimary("a", mustGet(t, r, "a").Epoch) {
		t.Fatal("a rejoined as a replica")
	}
}

// TestElectionWithNoSurvivingCandidate: every candidate dies during the election
// window; the shard stays without a primary and the next registration opens a new
// election (instead of the shard being stuck forever).
func TestElectionWithNoSurvivingCandidate(t *testing.T) {
	r, c := newReg()
	mustRegister(t, r, info("a", 0, base))
	r.MarkDown("a", mustGet(t, r, "a").Epoch, "died while electing")
	c.advance(10 * time.Second)
	r.Sweep()
	if _, ok := r.Primary(0); ok {
		t.Fatal("a DOWN node must not be elected")
	}
	mustRegister(t, r, info("b", 0, base))
	if _, ok := r.Primary(0); ok {
		t.Fatal("primary chosen before the new window closed")
	}
	c.advance(10 * time.Second)
	r.Sweep()
	if p, ok := r.Primary(0); !ok || p.ID != "b" {
		t.Fatalf("primary = %+v, %v; want b from the second election", p, ok)
	}
}

func TestSnapshotOrderAndSweeper(t *testing.T) {
	r, c := newReg()
	for _, n := range []model.NodeInfo{info("z", 2, base), info("b", 0, base), info("m", 1, base), info("a", 0, base.Add(time.Second))} {
		mustRegister(t, r, n)
	}
	c.advance(10 * time.Second)
	r.Sweep() // elections close: a (newest) wins shard 0, m shard 1, z shard 2

	var got []string
	for _, n := range r.Snapshot() {
		got = append(got, fmt.Sprintf("%d:%s:%s", n.Shard, n.ID, n.Role))
	}
	want := "[0:a:PRIMARY 0:b:REPLICA 1:m:PRIMARY 2:z:PRIMARY]"
	if fmt.Sprint(got) != want {
		t.Fatalf("Snapshot = %v, want %s (by shard, primary first, then ID)", got, want)
	}

	// RunSweeper drives Sweep on its own: after the timeout passes, nodes go DOWN.
	c.advance(time.Minute)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { r.RunSweeper(ctx, time.Millisecond); close(done) }()
	deadline := time.Now().Add(time.Second)
	for mustGet(t, r, "z").State != StateDown {
		if time.Now().After(deadline) {
			t.Fatal("RunSweeper never marked the silent node DOWN")
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	<-done // and it stops when its context is cancelled
}
