package store

import (
	"context"
	"fmt"
	"sync"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
)

// writeGate guarantees that a shard never has writes in flight on two different
// nodes at the same time.
//
// Why: when a primary is replaced (failover), writes already sent to the old primary
// may still be running. If the new primary accepted a write for the same key
// meanwhile, the old primary's late replica copy (same version number) could
// overwrite it on the new primary, and two callers could both be told their claim
// succeeded. So a write to the new primary waits until every write to the old one
// has finished (each is bounded by STORAGE_TIMEOUT). In normal operation all writes
// of a shard go to the same node and never wait.
type writeGate struct {
	mu      sync.Mutex
	active  []map[string]int // per shard: node incarnation ("id#epoch") → writes in flight
	changed chan struct{}    // closed and replaced whenever a write finishes
}

func newWriteGate(shards int) *writeGate {
	g := &writeGate{active: make([]map[string]int, shards), changed: make(chan struct{})}
	for i := range g.active {
		g.active[i] = map[string]int{}
	}
	return g
}

// enter blocks until no other node incarnation of shard has writes in flight, then
// registers a write to n. The returned leave must be called when the write (including
// its replication) is over.
func (g *writeGate) enter(ctx context.Context, shard int, n registry.Node) (leave func(), err error) {
	id := fmt.Sprintf("%s#%d", n.ID, n.Epoch)
	for {
		g.mu.Lock()
		if !g.othersBusyLocked(shard, id) {
			g.active[shard][id]++
			g.mu.Unlock()
			return func() { g.leave(shard, id) }, nil
		}
		wait := g.changed
		g.mu.Unlock()
		select {
		case <-wait:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

func (g *writeGate) leave(shard int, id string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.active[shard][id]--; g.active[shard][id] <= 0 {
		delete(g.active[shard], id)
	}
	close(g.changed) // wake every waiter; each re-checks its own shard
	g.changed = make(chan struct{})
}

func (g *writeGate) othersBusyLocked(shard int, id string) bool {
	for other, n := range g.active[shard] {
		if other != id && n > 0 {
			return true
		}
	}
	return false
}
