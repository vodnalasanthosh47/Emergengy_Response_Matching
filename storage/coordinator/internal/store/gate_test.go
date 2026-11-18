package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
)

func TestWriteGate(t *testing.T) {
	g := newWriteGate(3)
	oldPrimary := registry.Node{ID: "a", Epoch: 1}
	newPrimary := registry.Node{ID: "b", Epoch: 2}
	ctx := context.Background()

	// Many writes to the same node never wait for each other.
	leave1, err := g.enter(ctx, 0, oldPrimary)
	if err != nil {
		t.Fatal(err)
	}
	leave2, err := g.enter(ctx, 0, oldPrimary)
	if err != nil {
		t.Fatal(err)
	}
	// Other shards are independent.
	leaveOther, err := g.enter(ctx, 1, newPrimary)
	if err != nil {
		t.Fatal(err)
	}
	leaveOther()

	// A write to another node of shard 0 waits until both in-flight writes are done.
	entered := make(chan func(), 1)
	go func() {
		leave, err := g.enter(ctx, 0, newPrimary)
		if err != nil {
			t.Error(err)
		}
		entered <- leave
	}()
	select {
	case <-entered:
		t.Fatal("write to the new primary did not wait for the old primary's writes")
	case <-time.After(50 * time.Millisecond):
	}
	leave1()
	select {
	case <-entered:
		t.Fatal("entered while one write to the old primary was still in flight")
	case <-time.After(50 * time.Millisecond):
	}
	leave2()
	select {
	case leave := <-entered:
		leave()
	case <-time.After(time.Second):
		t.Fatal("write to the new primary never proceeded")
	}

	// The same node with a new epoch (it re-registered) counts as a different node.
	leaveOld, _ := g.enter(ctx, 2, oldPrimary)
	cctx, cancel := context.WithTimeout(ctx, 30*time.Millisecond)
	defer cancel()
	if _, err := g.enter(cctx, 2, registry.Node{ID: "a", Epoch: 9}); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("enter with a cancelled context = %v, want DeadlineExceeded", err)
	}
	leaveOld()
}
