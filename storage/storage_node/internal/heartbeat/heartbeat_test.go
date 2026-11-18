package heartbeat

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
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
