package store

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
)

func ptr(v int64) *int64 { return &v }

const tombJSON = `{"key":"request:1","value":{},"indexes":{"collection":"request"},"version":5,"updatedAt":"2026-11-14T06:53:00Z","deleted":true}`

const recordJSON = `{"key":"request:1","value":{"a":1},"indexes":{"collection":"request"},"version":2,"updatedAt":"2026-11-14T06:53:00Z"}`

// fakeNode starts an httptest.Server that answers every request with status and body.
func fakeNode(t *testing.T, status int, body string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestForwarderPutRelaysStatusCodes(t *testing.T) {
	errBody := `{"error":"X","message":"node says no","timestamp":"2026-11-14T06:53:00Z"}`
	tests := []struct {
		name        string
		status      int
		body        string
		wantCreated bool
		wantErr     error
	}{
		{"201 created", 201, recordJSON, true, nil},
		{"200 replaced", 200, recordJSON, false, nil},
		{"404", 404, errBody, false, ErrNotFound},
		{"409", 409, errBody, false, ErrVersionConflict},
		{"412", 412, errBody, false, ErrPreconditionFailed},
		{"400", 400, errBody, false, ErrInvalidRequest},
		{"500 becomes unavailable", 500, errBody, false, ErrUnavailable},
		{"503 becomes unavailable", 503, errBody, false, ErrUnavailable},
		{"garbage 200 body", 200, `not json`, false, ErrUnavailable},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := NewForwarder(fakeNode(t, tt.status, tt.body).URL, time.Second)
			rec, created, err := f.Put(context.Background(), "request:1", model.PutRequest{})

			if !errors.Is(err, tt.wantErr) || (tt.wantErr == nil && err != nil) {
				t.Fatalf("err = %v, want %v", err, tt.wantErr)
			}
			if created != tt.wantCreated {
				t.Errorf("created = %v, want %v", created, tt.wantCreated)
			}
			if tt.wantErr == nil && (rec.Key != "request:1" || rec.Version != 2) {
				t.Errorf("record not decoded: %+v", rec)
			}
		})
	}
}

func TestForwarder400KeepsNodeMessage(t *testing.T) {
	f := NewForwarder(fakeNode(t, 400, `{"error":"INVALID_REQUEST","message":"value must be a JSON object"}`).URL, time.Second)
	_, err := f.Get(context.Background(), "request:1")
	if !errors.Is(err, ErrInvalidRequest) || err.Error() != "invalid request: value must be a JSON object" {
		t.Fatalf("err = %v", err)
	}
}

// captured is what the fake node saw.
type captured struct {
	method, path, query, contentType string
	body                             map[string]any
}

func capturingNode(t *testing.T, status int, respBody string) (*httptest.Server, *captured) {
	t.Helper()
	got := &captured{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got.method, got.path, got.query = r.Method, r.URL.EscapedPath(), r.URL.RawQuery
		got.contentType = r.Header.Get("Content-Type")
		got.body = nil
		_ = json.NewDecoder(r.Body).Decode(&got.body)
		w.WriteHeader(status)
		_, _ = io.WriteString(w, respBody)
	}))
	t.Cleanup(srv.Close)
	return srv, got
}

func TestForwarderBuildsRequests(t *testing.T) {
	ctx := context.Background()

	t.Run("get", func(t *testing.T) {
		srv, got := capturingNode(t, 200, recordJSON)
		_, _ = NewForwarder(srv.URL, time.Second).Get(ctx, "resource:tdr1yz:abc")
		if got.method != "GET" || got.path != "/internal/data/resource:tdr1yz:abc" {
			t.Errorf("got %s %s", got.method, got.path)
		}
	})

	t.Run("put passes expectedVersion through, distinguishing omitted from 0", func(t *testing.T) {
		for _, tc := range []struct {
			ev   *int64
			want any // value of body["expectedVersion"]; nil = absent
		}{
			{nil, nil},
			{ptr(0), float64(0)},
			{ptr(6), float64(6)},
		} {
			srv, got := capturingNode(t, 200, recordJSON)
			req := model.PutRequest{Value: json.RawMessage(`{"x":1}`), Indexes: map[string]string{"collection": "r"}, ExpectedVersion: tc.ev}
			if _, _, err := NewForwarder(srv.URL, time.Second).Put(ctx, "request:1", req); err != nil {
				t.Fatal(err)
			}
			ev, present := got.body["expectedVersion"]
			if (tc.want == nil && present) || (tc.want != nil && ev != tc.want) {
				t.Errorf("expectedVersion %v: node got body %v", tc.ev, got.body)
			}
			if got.method != "PUT" || got.contentType != "application/json" {
				t.Errorf("got %s with content type %q", got.method, got.contentType)
			}
		}
	})

	t.Run("conditional delete uses query parameter", func(t *testing.T) {
		srv, got := capturingNode(t, 200, tombJSON)
		tomb, err := NewForwarder(srv.URL, time.Second).Delete(ctx, "request:1", ptr(4))
		if err != nil || !tomb.Deleted || tomb.Version != 5 {
			t.Fatalf("Delete = %+v, %v; want the tombstone", tomb, err)
		}
		if got.method != "DELETE" || got.path != "/internal/data/request:1" || got.query != "expectedVersion=4" {
			t.Errorf("got %s %s?%s", got.method, got.path, got.query)
		}
	})

	t.Run("unconditional delete has no query", func(t *testing.T) {
		srv, got := capturingNode(t, 200, tombJSON)
		_, _ = NewForwarder(srv.URL, time.Second).Delete(ctx, "request:1", nil)
		if got.query != "" {
			t.Errorf("query = %q, want empty", got.query)
		}
	})

	t.Run("query", func(t *testing.T) {
		srv, got := capturingNode(t, 200, `{"records":[`+recordJSON+`]}`)
		recs, err := NewForwarder(srv.URL, time.Second).Query(ctx, model.QueryRequest{Collection: "resource", GeoCells: []string{"tdr1yz"}})
		if err != nil || len(recs) != 1 {
			t.Fatalf("Query = %v, %v", recs, err)
		}
		if got.method != "POST" || got.path != "/internal/query" || got.body["collection"] != "resource" {
			t.Errorf("got %s %s %v", got.method, got.path, got.body)
		}
	})

	t.Run("base URL trailing slash is tolerated", func(t *testing.T) {
		srv, got := capturingNode(t, 200, recordJSON)
		_, _ = NewForwarder(srv.URL+"/", time.Second).Get(ctx, "request:1")
		if got.path != "/internal/data/request:1" {
			t.Errorf("path = %s", got.path)
		}
	})
}

func TestForwarderQueryNullRecordsBecomesEmpty(t *testing.T) {
	f := NewForwarder(fakeNode(t, 200, `{"records":null}`).URL, time.Second)
	recs, err := f.Query(context.Background(), model.QueryRequest{Collection: "r"})
	if err != nil || recs == nil || len(recs) != 0 {
		t.Fatalf("Query = %#v, %v; want empty non-nil slice", recs, err)
	}
}

func TestForwarderTimeoutIsUnavailable(t *testing.T) {
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select { // hang until the client gives up (or 5s, as a safety net)
		case <-r.Context().Done():
		case <-time.After(5 * time.Second):
		}
	}))
	t.Cleanup(slow.Close)

	start := time.Now()
	_, err := NewForwarder(slow.URL, 50*time.Millisecond).Get(context.Background(), "request:1")
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("err = %v, want ErrUnavailable", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("took %v; the timeout was not applied", elapsed)
	}
}

func TestForwarderConnectionRefusedIsUnavailable(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	url := srv.URL
	srv.Close() // nothing listens on this address any more

	_, err := NewForwarder(url, time.Second).Get(context.Background(), "request:1")
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("err = %v, want ErrUnavailable", err)
	}
}

func TestForwarderReady(t *testing.T) {
	if err := NewForwarder(fakeNode(t, 200, `{"status":"ready"}`).URL, time.Second).Ready(context.Background()); err != nil {
		t.Errorf("Ready with healthy node = %v", err)
	}
	if err := NewForwarder(fakeNode(t, 503, `{}`).URL, time.Second).Ready(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Errorf("Ready with unready node = %v, want ErrUnavailable", err)
	}
}
