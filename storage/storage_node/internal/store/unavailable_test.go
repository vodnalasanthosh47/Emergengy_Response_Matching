package store

// An unreachable MongoDB must surface as ErrUnavailable (→ HTTP 503, "retry later"),
// never as a generic error (→ HTTP 500), for every operation. This needs no Docker:
// nothing listens on port 1, so the driver fails server selection after 200 ms.

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

func TestUnreachableMongoIsUnavailable(t *testing.T) {
	t.Parallel()
	client, err := mongo.Connect(options.Client().ApplyURI("mongodb://127.0.0.1:1").
		SetServerSelectionTimeout(200 * time.Millisecond).SetConnectTimeout(200 * time.Millisecond))
	if err != nil {
		t.Fatal(err)
	}
	s := New(client.Database("x"))
	ctx := context.Background()
	ev := int64(3)
	ops := map[string]func() error{
		"Ping": func() error { return s.Ping(ctx) },
		"Get":  func() error { _, err := s.Get(ctx, "k"); return err },
		"Put": func() error {
			_, _, err := s.Put(ctx, "k", model.PutRequest{Value: json.RawMessage(`{}`), Indexes: map[string]string{"collection": "c"}})
			return err
		},
		"PutCAS": func() error {
			_, _, err := s.Put(ctx, "k", model.PutRequest{Value: json.RawMessage(`{}`), Indexes: map[string]string{"collection": "c"}, ExpectedVersion: &ev})
			return err
		},
		"Delete": func() error { _, err := s.Delete(ctx, "k", nil); return err },
		"Query":  func() error { _, err := s.Query(ctx, model.QueryRequest{Collection: "c"}); return err },
		"ApplyReplica": func() error {
			_, err := s.ApplyReplica(ctx, model.Record{Key: "k", Value: json.RawMessage(`{}`), Version: 1, UpdatedAt: time.Now()})
			return err
		},
		"Since":         func() error { _, err := s.Since(ctx, time.Time{}, "", 10); return err },
		"Stats":         func() error { _, err := s.Stats(ctx); return err },
		"MaxUpdatedAt":  func() error { _, err := s.MaxUpdatedAt(ctx); return err },
		"EnsureIndexes": func() error { return s.EnsureIndexes(ctx) },
	}
	for name, op := range ops {
		if err := op(); !errors.Is(err, ErrUnavailable) {
			t.Errorf("%s: got %v, want ErrUnavailable", name, err)
		}
	}
	// A cancelled caller is also reported as unavailable, not as a 500.
	cctx, cancel := context.WithCancel(ctx)
	cancel()
	if _, err := s.Get(cctx, "k"); !errors.Is(err, ErrUnavailable) {
		t.Errorf("Get with cancelled ctx: %v", err)
	}
}
