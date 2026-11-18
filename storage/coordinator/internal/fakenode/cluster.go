package fakenode

import (
	"fmt"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
)

// Clock is a manually advanced clock for the registry.
type Clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *Clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *Clock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// Cluster is a registry with fake nodes registered in it.
type Cluster struct {
	Reg    *registry.Registry
	Clock  *Clock
	Shards [][]*Node // Shards[s][i] is node "storage-s-i"
	Log    *slog.Logger
}

const (
	HeartbeatTimeout = 15 * time.Second
	ElectionWindow   = 10 * time.Second
)

// NewCluster starts shards×perShard empty nodes, registers them, elects
// storage-s-0 primary of each shard and marks the others ACTIVE replicas.
func NewCluster(t *testing.T, shards, perShard int) *Cluster {
	t.Helper()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	c := &Cluster{Clock: &Clock{t: time.Date(2026, 11, 17, 10, 0, 0, 0, time.UTC)}, Log: log}
	c.Reg = registry.New(registry.Config{Shards: shards, HeartbeatTimeout: HeartbeatTimeout,
		ElectionWindow: ElectionWindow, Now: c.Clock.Now, Log: log})
	for s := range shards {
		var nodes []*Node
		for i := range perShard {
			n := Start(t, fmt.Sprintf("storage-%d-%d", s, i))
			if _, err := c.Reg.Register(c.Info(n, s)); err != nil {
				t.Fatal(err)
			}
			nodes = append(nodes, n)
		}
		c.Shards = append(c.Shards, nodes)
	}
	c.Clock.Advance(ElectionWindow)
	c.Reg.Sweep()
	for _, task := range c.DrainSyncTasks() {
		c.Reg.SetActive(task) // every node starts empty, so they are already in sync
	}
	for s := range shards {
		if p, ok := c.Reg.Primary(s); !ok || p.ID != fmt.Sprintf("storage-%d-0", s) {
			t.Fatalf("shard %d primary = %+v, %v", s, p, ok)
		}
	}
	return c
}

// Info is the registration/heartbeat body for node n of shard s.
func (c *Cluster) Info(n *Node, shard int) model.NodeInfo {
	var max time.Time
	for _, r := range n.Records() {
		if r.UpdatedAt.After(max) {
			max = r.UpdatedAt
		}
	}
	return model.NodeInfo{NodeID: n.ID, ShardID: shard, URL: n.URL, MaxUpdatedAt: max}
}

// DrainSyncTasks returns the queued sync tasks without blocking.
func (c *Cluster) DrainSyncTasks() []registry.SyncTask {
	var out []registry.SyncTask
	for {
		select {
		case task := <-c.Reg.SyncTasks():
			out = append(out, task)
		default:
			return out
		}
	}
}
