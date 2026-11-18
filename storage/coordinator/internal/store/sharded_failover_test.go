package store_test

// Regression tests for claims racing a failover. Both scenarios let two matchers
// claim the same resource version while the shard's primary is being replaced; the
// rule under test is the project's hard requirement: never double-book.

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/routing"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

type claimResult struct {
	rec model.Record
	err error
}

func claim(s *store.ShardedStore, key, by string, version int64) claimResult {
	rec, _, err := s.Put(context.Background(), key, model.PutRequest{
		Value:           json.RawMessage(`{"claimedBy":"` + by + `"}`),
		Indexes:         map[string]string{"collection": "resource", "geoCell": "tdr1yz"},
		ExpectedVersion: &version,
	})
	return claimResult{rec, err}
}

func claimAsync(s *store.ShardedStore, key, by string, version int64) <-chan claimResult {
	ch := make(chan claimResult, 1)
	go func() { ch <- claim(s, key, by, version) }()
	return ch
}

// TestClaimOnReplacedPrimaryIsNotAcknowledged: claim A is running on the primary
// when the primary is replaced. A's write never reaches the new primary, so it must
// not be reported as a success; otherwise claim B, reading the same version from the
// new primary, also succeeds and the resource is booked twice.
func TestClaimOnReplacedPrimaryIsNotAcknowledged(t *testing.T) {
	s, c := newStore(t)
	key := resourceKey("tdr1yz", 1)
	shard := routing.ShardFor(key, 3)
	oldPrimary, replica := c.Shards[shard][0], c.Shards[shard][1]
	v := putResource(t, s, key).Version

	oldPrimary.PutDelay.Store(int64(300 * time.Millisecond)) // A is slow on the primary
	a := claimAsync(s, key, "A", v)
	time.Sleep(50 * time.Millisecond) // A is now in flight on the primary

	p, _ := c.Reg.Get(oldPrimary.ID)
	c.Reg.MarkDown(oldPrimary.ID, p.Epoch, "test: a concurrent call to the primary timed out")
	if np, _ := c.Reg.Primary(shard); np.ID != replica.ID {
		t.Fatalf("primary = %s, want %s promoted", np.ID, replica.ID)
	}

	resA := <-a
	if !errors.Is(resA.err, store.ErrUnavailable) {
		t.Fatalf("claim A on the replaced primary = %+v, %v; want ErrUnavailable (outcome unknown)", resA.rec, resA.err)
	}

	// B re-reads (from the new primary, which never saw A) and claims.
	got, err := s.Get(context.Background(), key)
	if err != nil {
		t.Fatal(err)
	}
	resB := claim(s, key, "B", got.Version)
	if resB.err != nil {
		t.Fatalf("claim B on the new primary = %v", resB.err)
	}
	final, _ := s.Get(context.Background(), key)
	if string(final.Value) != `{"claimedBy":"B"}` {
		t.Errorf("stored claim = %s, want B's (the only acknowledged one)", final.Value)
	}
}

// TestNewPrimaryWaitsForWritesInFlightOnTheOldOne: claim A is applied by the
// primary and is being copied to the replica when the replica is promoted. Claim B
// arrives at the new primary meanwhile, holding the same version as A did. Without
// the write gate, B succeeds and A's late copy (same version number) then overwrites
// it: B was told "claimed" but the stored claim is A's. With the gate, B waits for A
// to finish, then sees A's version and gets 409. Never may two claims be acknowledged,
// and the stored value must be the acknowledged one.
func TestNewPrimaryWaitsForWritesInFlightOnTheOldOne(t *testing.T) {
	s, c := newStore(t)
	key := resourceKey("tdr1yz", 2)
	shard := routing.ShardFor(key, 3)
	oldPrimary, replica := c.Shards[shard][0], c.Shards[shard][1]
	v := putResource(t, s, key).Version

	replica.ReplicaDelay.Store(int64(300 * time.Millisecond)) // A's copy to the replica is slow
	a := claimAsync(s, key, "A", v)
	time.Sleep(50 * time.Millisecond) // A applied on the primary, copy to the replica in flight

	p, _ := c.Reg.Get(oldPrimary.ID)
	c.Reg.MarkDown(oldPrimary.ID, p.Epoch, "test: a concurrent call to the primary timed out")
	if np, _ := c.Reg.Primary(shard); np.ID != replica.ID {
		t.Fatalf("primary = %s, want %s promoted", np.ID, replica.ID)
	}
	resB := claim(s, key, "B", v) // B read version v before A landed
	resA := <-a

	t.Logf("A: err=%v; B: err=%v", resA.err, resB.err)
	if resA.err == nil && resB.err == nil {
		t.Fatalf("DOUBLE BOOKING: both claims on version %d were acknowledged", v)
	}
	final, err := s.Get(context.Background(), key)
	if err != nil {
		t.Fatal(err)
	}
	if resB.err == nil && string(final.Value) != `{"claimedBy":"B"}` {
		t.Fatalf("B was acknowledged but the stored claim is %s (B's write was lost)", final.Value)
	}
	if resA.err == nil && string(final.Value) != `{"claimedBy":"A"}` {
		t.Fatalf("A was acknowledged but the stored claim is %s (A's write was lost)", final.Value)
	}
	if !errors.Is(resB.err, store.ErrVersionConflict) {
		t.Errorf("claim B = %v, want ErrVersionConflict (A's write reached the new primary first)", resB.err)
	}
}
