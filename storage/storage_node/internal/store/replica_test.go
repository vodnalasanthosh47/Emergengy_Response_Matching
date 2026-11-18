package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

var t0 = time.Date(2026, 11, 14, 6, 53, 0, 0, time.UTC)

func replicaRec(key string, version int64, value string, at time.Time) model.Record {
	return model.Record{Key: key, Value: json.RawMessage(value),
		Indexes: map[string]string{"collection": "request", "status": "PENDING"}, Version: version, UpdatedAt: at}
}

func TestApplyReplicaKeepsNewestVersion(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	key := "request:r1"

	// v2 arrives before v1 (out of order): v2 is applied, the late v1 is ignored.
	if ok, err := s.ApplyReplica(ctx, replicaRec(key, 2, `{"n":2}`, t0.Add(time.Second))); err != nil || !ok {
		t.Fatalf("apply v2 = %v, %v", ok, err)
	}
	if ok, err := s.ApplyReplica(ctx, replicaRec(key, 1, `{"n":1}`, t0)); err != nil || ok {
		t.Fatalf("apply stale v1 = %v, %v; want ignored", ok, err)
	}
	got, err := s.Get(ctx, key)
	if err != nil || got.Version != 2 || !jsonEqual(t, got.Value, `{"n":2}`) || !got.UpdatedAt.Equal(t0.Add(time.Second)) {
		t.Fatalf("Get = %+v, %v; want the v2 copy with the primary's timestamp", got, err)
	}

	// Equal version: the incoming (primary's) copy wins over a divergent one.
	if ok, err := s.ApplyReplica(ctx, replicaRec(key, 2, `{"n":"primary"}`, t0.Add(2*time.Second))); err != nil || !ok {
		t.Fatalf("apply equal version = %v, %v", ok, err)
	}
	got, _ = s.Get(ctx, key)
	if !jsonEqual(t, got.Value, `{"n":"primary"}`) {
		t.Errorf("equal-version replica not applied: %s", got.Value)
	}

	// A replicated tombstone hides the record; a CAS write on this node then sees it as gone.
	tomb := model.Record{Key: key, Version: 3, UpdatedAt: t0.Add(3 * time.Second), Deleted: true}
	if ok, err := s.ApplyReplica(ctx, tomb); err != nil || !ok {
		t.Fatalf("apply tombstone = %v, %v", ok, err)
	}
	if _, err := s.Get(ctx, key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Get after replicated delete = %v, want ErrNotFound", err)
	}
	any, err := s.GetAny(ctx, key)
	if err != nil || !any.Deleted || any.Version != 3 {
		t.Fatalf("GetAny = %+v, %v; want the tombstone", any, err)
	}
}

func TestRecreateAfterDeleteKeepsCountingVersions(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	key := "request:r2"
	if _, _, err := s.Put(ctx, key, putReq(`{}`, ptr(0))); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Delete(ctx, key, nil); err != nil {
		t.Fatal(err)
	}
	// Create-only succeeds on a tombstone, and the version continues (3, not 1), so a
	// client holding the old "version 1" cannot pass a CAS on the new record.
	rec, created, err := s.Put(ctx, key, putReq(`{"again":true}`, ptr(0)))
	if err != nil || !created || rec.Version != 3 {
		t.Fatalf("re-create = (v%d, created %v, %v), want (3, true, nil)", rec.Version, created, err)
	}
	if _, _, err := s.Put(ctx, key, putReq(`{}`, ptr(1))); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("CAS with pre-delete version = %v, want ErrVersionConflict", err)
	}
	// Unconditional upsert over a tombstone reports "created".
	if _, err := s.Delete(ctx, key, nil); err != nil {
		t.Fatal(err)
	}
	rec, created, err = s.Put(ctx, key, putReq(`{}`, nil))
	if err != nil || !created || rec.Version != 5 {
		t.Fatalf("upsert over tombstone = (v%d, created %v, %v), want (5, true, nil)", rec.Version, created, err)
	}
	got, _ := s.Get(ctx, key)
	if got.Version != rec.Version || !got.UpdatedAt.Equal(rec.UpdatedAt) {
		t.Errorf("returned record %+v differs from stored %+v", rec, got)
	}
}

func TestRepairAndPurgeAreConditional(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	key := "request:r3"
	if _, err := s.ApplyReplica(ctx, replicaRec(key, 4, `{"mine":true}`, t0)); err != nil {
		t.Fatal(err)
	}
	// Repair against a version the node no longer holds is refused.
	if ok, err := s.Repair(ctx, replicaRec(key, 3, `{"primary":true}`, t0), 9); err != nil || ok {
		t.Fatalf("repair with wrong local version = %v, %v", ok, err)
	}
	// With the right local version the primary's copy replaces ours even though it is
	// older: this is how an unreplicated write of a failed primary is undone.
	if ok, err := s.Repair(ctx, replicaRec(key, 3, `{"primary":true}`, t0), 4); err != nil || !ok {
		t.Fatalf("repair = %v, %v", ok, err)
	}
	got, _ := s.Get(ctx, key)
	if got.Version != 3 || !jsonEqual(t, got.Value, `{"primary":true}`) {
		t.Fatalf("after repair = %+v", got)
	}
	if ok, _ := s.Purge(ctx, key, 4); ok {
		t.Fatal("purge with wrong local version removed the record")
	}
	if ok, err := s.Purge(ctx, key, 3); err != nil || !ok {
		t.Fatalf("purge = %v, %v", ok, err)
	}
	if _, err := s.GetAny(ctx, key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetAny after purge = %v", err)
	}
}

func TestSincePagesWithoutSkippingEqualTimestamps(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	// 7 records share one timestamp, 3 are later; one is a tombstone.
	want := map[string]bool{}
	for i := 0; i < 10; i++ {
		at := t0
		if i >= 7 {
			at = t0.Add(time.Duration(i) * time.Second)
		}
		rec := replicaRec(fmt.Sprintf("request:c%02d", i), 1, `{}`, at)
		rec.Deleted = i == 8
		if _, err := s.ApplyReplica(ctx, rec); err != nil {
			t.Fatal(err)
		}
		want[rec.Key] = true
	}
	// An older record outside the window must not be returned.
	if _, err := s.ApplyReplica(ctx, replicaRec("request:c-old", 1, `{}`, t0.Add(-time.Hour))); err != nil {
		t.Fatal(err)
	}

	seen := map[string]bool{}
	since, after := t0.Add(-time.Millisecond), ""
	for pages := 0; ; pages++ {
		if pages > 10 {
			t.Fatal("paging does not terminate")
		}
		page, err := s.Since(ctx, since, after, 3)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range page.Records {
			if seen[r.Key] {
				t.Errorf("%s returned twice", r.Key)
			}
			seen[r.Key] = true
		}
		if !page.More {
			break
		}
		since, after = page.NextSince, page.NextKey
	}
	if len(seen) != len(want) {
		t.Errorf("got %d records %v, want %d", len(seen), seen, len(want))
	}
	for k := range want {
		if !seen[k] {
			t.Errorf("missing %s", k)
		}
	}
}

func TestStatsAndMaxUpdatedAt(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	if m, err := s.MaxUpdatedAt(ctx); err != nil || !m.IsZero() {
		t.Fatalf("empty MaxUpdatedAt = %v, %v", m, err)
	}
	_, _ = s.ApplyReplica(ctx, replicaRec("request:s1", 1, `{}`, t0))
	_, _ = s.ApplyReplica(ctx, replicaRec("request:s2", 1, `{}`, t0.Add(time.Minute)))
	_, _ = s.ApplyReplica(ctx, model.Record{Key: "request:s3", Version: 2, UpdatedAt: t0, Deleted: true})
	st, err := s.Stats(ctx)
	if err != nil || st.Records != 2 || st.Tombstones != 1 || !st.MaxUpdatedAt.Equal(t0.Add(time.Minute)) {
		t.Fatalf("Stats = %+v, %v", st, err)
	}
}
