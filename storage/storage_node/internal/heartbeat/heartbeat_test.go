package heartbeat

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

// TestRegistersThenHeartbeatsAndReRegistersOn404 drives the client against a fake
// coordinator that forgets the node once (as after a coordinator restart).
func TestRegistersThenHeartbeatsAndReRegistersOn404(t *testing.T) {
	var mu sync.Mutex
	var paths []string
	forgot := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var info model.NodeInfo
		_ = json.NewDecoder(r.Body).Decode(&info)
		mu.Lock()
		defer mu.Unlock()
		paths = append(paths, r.URL.Path)
		if info.NodeID != "n1" || info.ShardID != 2 || info.URL != "http://n1:8080" {
			t.Errorf("unexpected body %+v", info)
		}
		if r.URL.Path == "/internal/nodes/heartbeat" && !forgot {
			forgot = true
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = io.WriteString(w, `{"role":"REPLICA"}`)
	}))
	defer srv.Close()

	ctx, cancel := context.WithCancel(context.Background())
	c := &Client{CoordinatorURL: srv.URL, NodeID: "n1", ShardID: 2, URL: "http://n1:8080",
		Interval:     10 * time.Millisecond,
		MaxUpdatedAt: func(context.Context) (time.Time, error) { return time.Time{}, nil },
		HTTP:         srv.Client(), Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	done := make(chan struct{})
	go func() { c.Run(ctx); close(done) }()
	time.Sleep(80 * time.Millisecond)
	cancel()
	<-done

	mu.Lock()
	defer mu.Unlock()
	want := []string{"/internal/nodes/register", "/internal/nodes/heartbeat", "/internal/nodes/register", "/internal/nodes/heartbeat"}
	if len(paths) < len(want) {
		t.Fatalf("paths = %v", paths)
	}
	for i, p := range want {
		if paths[i] != p {
			t.Fatalf("paths = %v, want prefix %v", paths, want)
		}
	}
}

// TestRegister404DoesNotSpin: if registration itself answers 404 (COORDINATOR_URL
// points at the wrong service), the client must retry on its normal schedule, not in a
// tight loop. Before the fix it sent ~14,000 requests in half a second.
func TestRegister404DoesNotSpin(t *testing.T) {
	var calls atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/internal/nodes/register" {
			t.Errorf("unregistered node sent %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer cancel()
	c := &Client{CoordinatorURL: srv.URL, NodeID: "n1", ShardID: 0, URL: "http://n1:8080",
		Interval:     100 * time.Millisecond,
		MaxUpdatedAt: func(context.Context) (time.Time, error) { return time.Time{}, nil },
		HTTP:         srv.Client(), Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	c.Run(ctx)

	// One attempt at once, then one per interval: 3 in 250 ms (allow a little slack).
	if n := calls.Load(); n < 2 || n > 4 {
		t.Fatalf("%d register attempts in 250ms with a 100ms interval, want about 3", n)
	}
}

// TestCoordinatorUnreachableKeepsRetrying: the coordinator may start after the node.
func TestCoordinatorUnreachableKeepsRetrying(t *testing.T) {
	var mu sync.Mutex
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		paths = append(paths, r.URL.Path)
		n := len(paths)
		mu.Unlock()
		if n <= 2 { // the first two attempts fail as if the coordinator were down
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		_, _ = io.WriteString(w, `{"role":"REPLICA"}`)
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
	defer cancel()
	c := &Client{CoordinatorURL: srv.URL + "/", NodeID: "n1", ShardID: 0, URL: "http://n1:8080",
		Interval:     20 * time.Millisecond,
		MaxUpdatedAt: func(context.Context) (time.Time, error) { return time.Time{}, nil },
		HTTP:         srv.Client(), Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	c.Run(ctx)

	mu.Lock()
	defer mu.Unlock()
	// register (503), register (503), register (ok), then heartbeats.
	if len(paths) < 4 || paths[0] != "/internal/nodes/register" || paths[2] != "/internal/nodes/register" || paths[3] != "/internal/nodes/heartbeat" {
		t.Fatalf("paths = %v", paths)
	}
}
