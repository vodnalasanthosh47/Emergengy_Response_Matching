package store

// Integration tests: these run against a real, throwaway MongoDB started with
// testcontainers-go, so `go test ./...` works on any machine that has Docker.
// They are skipped with `go test -short` or when Docker is not available.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go"
	tcmongo "github.com/testcontainers/testcontainers-go/modules/mongodb"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

// Same major version as deploy/compose/docker-compose.yml.
const mongoImage = "mongo:8.0"

var (
	testClient *mongo.Client
	skipReason string
	dbCounter  atomic.Int64
)

func TestMain(m *testing.M) {
	flag.Parse()
	if testing.Short() {
		skipReason = "integration test skipped in -short mode"
		os.Exit(m.Run())
	}

	ctx := context.Background()
	container, err := tcmongo.Run(ctx, mongoImage)
	if err != nil {
		skipReason = fmt.Sprintf("cannot start MongoDB container (is Docker running?): %v", err)
		os.Exit(m.Run())
	}
	uri, err := container.ConnectionString(ctx)
	if err == nil {
		testClient, err = mongo.Connect(options.Client().ApplyURI(uri))
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "connect to test MongoDB:", err)
		_ = testcontainers.TerminateContainer(container)
		os.Exit(1)
	}

	code := m.Run()

	_ = testClient.Disconnect(ctx)
	_ = testcontainers.TerminateContainer(container)
	os.Exit(code)
}

// newTestStore returns a MongoStore on a fresh database that is dropped after the test.
func newTestStore(t *testing.T) *MongoStore {
	t.Helper()
	if testClient == nil {
		t.Skip(skipReason)
	}
	db := testClient.Database(fmt.Sprintf("test_%d", dbCounter.Add(1)))
	t.Cleanup(func() { _ = db.Drop(context.Background()) })

	s := New(db)
	if err := s.EnsureIndexes(testCtx(t)); err != nil {
		t.Fatalf("EnsureIndexes: %v", err)
	}
	return s
}

func testCtx(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func ptr(v int64) *int64 { return &v }

func putReq(value string, expected *int64) model.PutRequest {
	return model.PutRequest{
		Value:           json.RawMessage(value),
		Indexes:         map[string]string{"collection": "resource", "type": "WATER", "status": "AVAILABLE", "geoCell": "tdr1yz"},
		ExpectedVersion: expected,
	}
}

func TestUnconditionalPutCreatesThenIncrementsVersion(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)

	rec, created, err := s.Put(ctx, "resource:tdr1yz:a", putReq(`{"remaining":500}`, nil))
	if err != nil || !created || rec.Version != 1 {
		t.Fatalf("first Put = (version %d, created %v, %v), want (1, true, nil)", rec.Version, created, err)
	}

	rec, created, err = s.Put(ctx, "resource:tdr1yz:a", putReq(`{"remaining":480}`, nil))
	if err != nil || created || rec.Version != 2 {
		t.Fatalf("second Put = (version %d, created %v, %v), want (2, false, nil)", rec.Version, created, err)
	}

	got, err := s.Get(ctx, "resource:tdr1yz:a")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if got.Version != 2 || !jsonEqual(t, got.Value, `{"remaining":480}`) {
		t.Errorf("Get = version %d value %s, want version 2 value {\"remaining\":480}", got.Version, got.Value)
	}
	if got.UpdatedAt.IsZero() || !got.UpdatedAt.Equal(rec.UpdatedAt) {
		t.Errorf("UpdatedAt = %v, want %v (the write's timestamp)", got.UpdatedAt, rec.UpdatedAt)
	}
}

func TestValueRoundTripPreservesTypes(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	value := `{"type":"WATER","capacity":{"total":500,"remaining":480,"unit":"L"},"ratio":0.5,` +
		`"whole":20.0,"tags":["a","b"],"ok":true,"assignedResourceId":null,"big":9007199254740993}`

	if _, _, err := s.Put(ctx, "resource:tdr1yz:b", putReq(value, nil)); err != nil {
		t.Fatalf("Put: %v", err)
	}
	got, err := s.Get(ctx, "resource:tdr1yz:b")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if !jsonEqual(t, got.Value, value) {
		t.Errorf("value changed in round trip:\n got  %s\n want %s", got.Value, value)
	}
	// The reason we use Extended JSON: integers must come back as integers, not 480.0,
	// and int64 values beyond 2^53 must not lose precision.
	for _, want := range []string{`"remaining":480`, `"big":9007199254740993`} {
		if !bytes.Contains(compact(t, got.Value), []byte(want)) {
			t.Errorf("value %s does not contain %s", got.Value, want)
		}
	}
}

func TestCreateOnly(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)

	rec, created, err := s.Put(ctx, "request:1", putReq(`{}`, ptr(0)))
	if err != nil || !created || rec.Version != 1 {
		t.Fatalf("create-only on new key = (version %d, created %v, %v), want (1, true, nil)", rec.Version, created, err)
	}
	if _, _, err := s.Put(ctx, "request:1", putReq(`{}`, ptr(0))); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("create-only on existing key error = %v, want ErrVersionConflict", err)
	}
}

func TestCompareAndSet(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	if _, _, err := s.Put(ctx, "resource:tdr1yz:c", putReq(`{"remaining":500}`, nil)); err != nil {
		t.Fatalf("seed Put: %v", err)
	}

	rec, created, err := s.Put(ctx, "resource:tdr1yz:c", putReq(`{"remaining":480}`, ptr(1)))
	if err != nil || created || rec.Version != 2 {
		t.Fatalf("CAS with correct version = (version %d, created %v, %v), want (2, false, nil)", rec.Version, created, err)
	}
	if _, _, err := s.Put(ctx, "resource:tdr1yz:c", putReq(`{"remaining":460}`, ptr(1))); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("CAS with stale version error = %v, want ErrVersionConflict", err)
	}
	if _, _, err := s.Put(ctx, "resource:tdr1yz:missing", putReq(`{}`, ptr(5))); !errors.Is(err, ErrPreconditionFailed) {
		t.Fatalf("CAS on missing key error = %v, want ErrPreconditionFailed", err)
	}

	got, _ := s.Get(ctx, "resource:tdr1yz:c")
	if !jsonEqual(t, got.Value, `{"remaining":480}`) {
		t.Errorf("rejected write changed the value: %s", got.Value)
	}
}

func TestDelete(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)

	if err := s.Delete(ctx, "request:nope", nil); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Delete missing = %v, want ErrNotFound", err)
	}
	if err := s.Delete(ctx, "request:nope", ptr(1)); !errors.Is(err, ErrNotFound) {
		t.Fatalf("conditional Delete missing = %v, want ErrNotFound", err)
	}

	if _, _, err := s.Put(ctx, "request:d", putReq(`{}`, nil)); err != nil {
		t.Fatalf("seed Put: %v", err)
	}
	if err := s.Delete(ctx, "request:d", ptr(2)); !errors.Is(err, ErrVersionConflict) {
		t.Fatalf("Delete with wrong version = %v, want ErrVersionConflict", err)
	}
	if err := s.Delete(ctx, "request:d", ptr(1)); err != nil {
		t.Fatalf("Delete with right version = %v, want nil", err)
	}
	if _, err := s.Get(ctx, "request:d"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Get after delete = %v, want ErrNotFound", err)
	}
}

func TestQuery(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	seed := []struct{ key, collection, typ, status, cell string }{
		{"resource:tdr1yz:3", "resource", "WATER", "AVAILABLE", "tdr1yz"},
		{"resource:tdr1yz:1", "resource", "WATER", "AVAILABLE", "tdr1yz"},
		{"resource:tdr1yy:2", "resource", "WATER", "DEPLETED", "tdr1yy"},
		{"resource:tdr1yy:4", "resource", "FOOD", "AVAILABLE", "tdr1yy"},
		{"resource:zzzzzz:5", "resource", "WATER", "AVAILABLE", "zzzzzz"},
		{"request:6", "request", "WATER", "PENDING", "tdr1yz"},
	}
	for _, r := range seed {
		req := model.PutRequest{
			Value:   json.RawMessage(`{}`),
			Indexes: map[string]string{"collection": r.collection, "type": r.typ, "status": r.status, "geoCell": r.cell},
		}
		if _, _, err := s.Put(ctx, r.key, req); err != nil {
			t.Fatalf("seed Put %s: %v", r.key, err)
		}
	}

	tests := []struct {
		name string
		q    model.QueryRequest
		want []string
	}{
		{"whole collection, sorted by key", model.QueryRequest{Collection: "resource"},
			[]string{"resource:tdr1yy:2", "resource:tdr1yy:4", "resource:tdr1yz:1", "resource:tdr1yz:3", "resource:zzzzzz:5"}},
		{"cells + type + status", model.QueryRequest{Collection: "resource", GeoCells: []string{"tdr1yz", "tdr1yy"},
			Match: map[string]string{"type": "WATER", "status": "AVAILABLE"}},
			[]string{"resource:tdr1yz:1", "resource:tdr1yz:3"}},
		{"limit", model.QueryRequest{Collection: "resource", Limit: 2},
			[]string{"resource:tdr1yy:2", "resource:tdr1yy:4"}},
		{"other collection", model.QueryRequest{Collection: "request"}, []string{"request:6"}},
		{"no matches gives empty list", model.QueryRequest{Collection: "resource", Match: map[string]string{"type": "SHELTER"}},
			[]string{}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			recs, err := s.Query(ctx, tt.q)
			if err != nil {
				t.Fatalf("Query: %v", err)
			}
			got := make([]string, 0, len(recs))
			for _, r := range recs {
				got = append(got, r.Key)
			}
			if !reflect.DeepEqual(got, tt.want) {
				t.Errorf("Query keys = %v, want %v", got, tt.want)
			}
		})
	}
}

// The core no-double-booking guarantee: N writers all holding version 1 race to
// update the same record — exactly one may succeed.
func TestConcurrentCompareAndSetExactlyOneWins(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	const key, writers = "resource:tdr1yz:race", 20
	if _, _, err := s.Put(ctx, key, putReq(`{"remaining":20}`, nil)); err != nil {
		t.Fatalf("seed Put: %v", err)
	}

	wins, conflicts, other := raceWriters(writers, func(i int) error {
		_, _, err := s.Put(ctx, key, putReq(fmt.Sprintf(`{"remaining":0,"claimedBy":%d}`, i), ptr(1)))
		return err
	})
	if wins != 1 || conflicts != writers-1 || other != nil {
		t.Fatalf("wins=%d conflicts=%d otherErr=%v; want exactly 1 win and %d conflicts", wins, conflicts, other, writers-1)
	}
	if got, _ := s.Get(ctx, key); got.Version != 2 {
		t.Errorf("final version = %d, want 2", got.Version)
	}
}

func TestConcurrentCreateOnlyExactlyOneWins(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	const writers = 20

	wins, conflicts, other := raceWriters(writers, func(i int) error {
		_, _, err := s.Put(ctx, "request:race", putReq(`{}`, ptr(0)))
		return err
	})
	if wins != 1 || conflicts != writers-1 || other != nil {
		t.Fatalf("wins=%d conflicts=%d otherErr=%v; want exactly 1 win and %d conflicts", wins, conflicts, other, writers-1)
	}
}

// Exercises the duplicate-key retry in upsert: concurrent unconditional writes to a new
// key must all succeed, and the version must count every one of them.
func TestConcurrentUnconditionalUpsertsAllSucceed(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t)
	const writers = 20

	wins, conflicts, other := raceWriters(writers, func(i int) error {
		_, _, err := s.Put(ctx, "request:upsert-race", putReq(`{}`, nil))
		return err
	})
	if wins != writers || conflicts != 0 || other != nil {
		t.Fatalf("wins=%d conflicts=%d otherErr=%v; want %d wins", wins, conflicts, other, writers)
	}
	if got, _ := s.Get(ctx, "request:upsert-race"); got.Version != writers {
		t.Errorf("final version = %d, want %d", got.Version, writers)
	}
}

func TestEnsureIndexesIsIdempotent(t *testing.T) {
	s, ctx := newTestStore(t), testCtx(t) // newTestStore already ran it once
	if err := s.EnsureIndexes(ctx); err != nil {
		t.Fatalf("second EnsureIndexes: %v", err)
	}
	specs, err := s.coll.Indexes().ListSpecifications(ctx)
	if err != nil {
		t.Fatalf("ListSpecifications: %v", err)
	}
	if len(specs) != 5 { // _id + our four
		t.Errorf("got %d indexes, want 5", len(specs))
	}
}

// raceWriters starts n goroutines that call write at (nearly) the same moment and
// counts successes, version conflicts, and the first unexpected error.
func raceWriters(n int, write func(i int) error) (wins, conflicts int, other error) {
	var (
		mu    sync.Mutex
		wg    sync.WaitGroup
		start = make(chan struct{})
	)
	for i := range n {
		wg.Go(func() {
			<-start // line everyone up so the writes really overlap
			err := write(i)
			mu.Lock()
			defer mu.Unlock()
			switch {
			case err == nil:
				wins++
			case errors.Is(err, ErrVersionConflict):
				conflicts++
			case other == nil:
				other = err
			}
		})
	}
	close(start)
	wg.Wait()
	return wins, conflicts, other
}

func compact(t *testing.T, b []byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := json.Compact(&buf, b); err != nil {
		t.Fatalf("compact %s: %v", b, err)
	}
	return buf.Bytes()
}

// jsonEqual compares two JSON documents semantically (ignoring whitespace and key order).
func jsonEqual(t *testing.T, got []byte, want string) bool {
	t.Helper()
	var g, w any
	dg := json.NewDecoder(bytes.NewReader(got))
	dg.UseNumber()
	dw := json.NewDecoder(bytes.NewReader([]byte(want)))
	dw.UseNumber()
	if err := dg.Decode(&g); err != nil {
		t.Fatalf("decode %s: %v", got, err)
	}
	if err := dw.Decode(&w); err != nil {
		t.Fatalf("decode %s: %v", want, err)
	}
	return reflect.DeepEqual(g, w)
}
