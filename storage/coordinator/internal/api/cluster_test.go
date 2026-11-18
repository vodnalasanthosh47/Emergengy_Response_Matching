package api

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
)

type fakeNodes struct {
	err  error
	got  model.NodeInfo
	path string
}

func (f *fakeNodes) Register(info model.NodeInfo) (registry.Role, error) {
	f.got, f.path = info, "register"
	return registry.RoleReplica, f.err
}
func (f *fakeNodes) Heartbeat(info model.NodeInfo) (registry.Role, error) {
	f.got, f.path = info, "heartbeat"
	return registry.RolePrimary, f.err
}

func testCluster(n NodeRegistry) Cluster {
	return Cluster{Nodes: n, Topology: func(context.Context) any { return []string{"shard-view"} }}
}

func TestNodeEndpoints(t *testing.T) {
	const body = `{"nodeId":"storage-0-0","shardId":0,"url":"http://storage-0-0:8080","maxUpdatedAt":"2026-11-14T06:53:00Z"}`
	for _, tc := range []struct {
		name, path string
		err        error
		wantStatus int
		wantRole   registry.Role
	}{
		{"register", "/internal/nodes/register", nil, 200, registry.RoleReplica},
		{"register invalid", "/internal/nodes/register", registry.ErrInvalidNode, 400, ""},
		{"heartbeat", "/internal/nodes/heartbeat", nil, 200, registry.RolePrimary},
		{"heartbeat unknown node", "/internal/nodes/heartbeat", registry.ErrUnknownNode, 404, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			nodes := &fakeNodes{err: tc.err}
			h := NewHandler(&fakeStore{}, testCluster(nodes), slog.New(slog.NewTextHandler(io.Discard, nil)), NewMetrics("c"), time.Second)
			rr := httptest.NewRecorder()
			h.ServeHTTP(rr, httptest.NewRequest("POST", tc.path, strings.NewReader(body)))
			if rr.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d: %s", rr.Code, tc.wantStatus, rr.Body)
			}
			if nodes.got.NodeID != "storage-0-0" || nodes.got.URL != "http://storage-0-0:8080" || nodes.got.MaxUpdatedAt.IsZero() {
				t.Errorf("registry got %+v", nodes.got)
			}
			if tc.wantRole != "" {
				var out roleResponse
				if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil || out.Role != tc.wantRole {
					t.Errorf("body = %s, want role %s", rr.Body, tc.wantRole)
				}
			}
		})
	}
}

func TestTopologyEndpoint(t *testing.T) {
	h := NewHandler(&fakeStore{}, testCluster(&fakeNodes{}), slog.New(slog.NewTextHandler(io.Discard, nil)), NewMetrics("c"), time.Second)
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/internal/topology", nil))
	if rr.Code != 200 || !strings.Contains(rr.Body.String(), `"shards":["shard-view"]`) {
		t.Fatalf("topology = %d %s", rr.Code, rr.Body)
	}
}

func TestClusterMetrics(t *testing.T) {
	m := NewMetrics("coordinator")
	m.WatchCluster(func() []registry.Node {
		return []registry.Node{{State: registry.StateActive}, {State: registry.StateActive}, {State: registry.StateDown}}
	}, func() uint64 { return 2 })
	rr := httptest.NewRecorder()
	m.Handler().ServeHTTP(rr, httptest.NewRequest("GET", "/metrics", nil))
	for _, want := range []string{
		`coordinator_storage_nodes{state="ACTIVE"} 2`,
		`coordinator_storage_nodes{state="DOWN"} 1`,
		`coordinator_failovers_total 2`,
	} {
		if !strings.Contains(rr.Body.String(), want) {
			t.Errorf("/metrics missing %q", want)
		}
	}
}
