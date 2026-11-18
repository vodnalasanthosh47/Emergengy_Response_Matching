package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

// fakeStore is an in-memory stand-in for the RecordStore: it returns the configured
// error and remembers what it was asked to do.
type fakeStore struct {
	err       error
	created   bool
	calls     int
	gotKey    string
	gotPut    model.PutRequest
	gotDelete *int64
	gotQuery  model.QueryRequest
}

var sampleRecord = model.Record{
	Key:       "request:1",
	Value:     json.RawMessage(`{"a":1}`),
	Indexes:   map[string]string{"collection": "request"},
	Version:   3,
	UpdatedAt: time.Date(2026, 11, 14, 6, 53, 0, 0, time.UTC),
}

func (f *fakeStore) Get(_ context.Context, key string) (model.Record, error) {
	f.calls++
	f.gotKey = key
	return sampleRecord, f.err
}
func (f *fakeStore) Put(_ context.Context, key string, req model.PutRequest) (model.Record, bool, error) {
	f.calls++
	f.gotKey, f.gotPut = key, req
	return sampleRecord, f.created, f.err
}
func (f *fakeStore) Delete(_ context.Context, key string, ev *int64) error {
	f.calls++
	f.gotKey, f.gotDelete = key, ev
	return f.err
}
func (f *fakeStore) Query(_ context.Context, q model.QueryRequest) ([]model.Record, error) {
	f.calls++
	f.gotQuery = q
	return []model.Record{sampleRecord}, f.err
}
func (f *fakeStore) Ready(context.Context) error { return f.err }

func newTestHandler(fs *fakeStore) (*Metrics, func(method, path, body string) *httptest.ResponseRecorder) {
	m := NewMetrics("coordinator")
	h := NewHandler(fs, testCluster(&fakeNodes{}), slog.New(slog.NewTextHandler(io.Discard, nil)), m, time.Second)
	return m, func(method, path, body string) *httptest.ResponseRecorder {
		rr := httptest.NewRecorder()
		h.ServeHTTP(rr, httptest.NewRequest(method, path, strings.NewReader(body)))
		return rr
	}
}

const validPut = `{"value":{"a":1},"indexes":{"collection":"request"}}`

func TestHandlers(t *testing.T) {
	tests := []struct {
		name        string
		method      string
		path        string
		body        string
		storeErr    error
		created     bool
		wantStatus  int
		wantCode    string
		wantNoStore bool // request must be rejected before reaching storage
	}{
		{"get ok", "GET", "/v1/data/request:1", "", nil, false, 200, "", false},
		{"get missing", "GET", "/v1/data/request:1", "", store.ErrNotFound, false, 404, codeNotFound, false},
		{"get bad key", "GET", "/v1/data/bad$key", "", nil, false, 400, codeInvalidRequest, true},
		{"get storage down", "GET", "/v1/data/request:1", "", store.ErrUnavailable, false, 503, codeStorageUnavailable, false},
		{"get unexpected", "GET", "/v1/data/request:1", "", errors.New("boom"), false, 500, codeInternalError, false},

		{"put created", "PUT", "/v1/data/request:1", validPut, nil, true, 201, "", false},
		{"put replaced", "PUT", "/v1/data/request:1", validPut, nil, false, 200, "", false},
		{"put conflict", "PUT", "/v1/data/request:1", validPut, store.ErrVersionConflict, false, 409, codeVersionConflict, false},
		{"put precondition", "PUT", "/v1/data/request:1", validPut, store.ErrPreconditionFailed, false, 412, codePreconditionFailed, false},
		{"put rejected by node", "PUT", "/v1/data/request:1", validPut, fmt.Errorf("%w: node says no", store.ErrInvalidRequest), false, 400, codeInvalidRequest, false},
		{"put malformed json", "PUT", "/v1/data/request:1", `{`, nil, false, 400, codeInvalidRequest, true},
		{"put unknown field", "PUT", "/v1/data/request:1", `{"value":{},"indexes":{"collection":"r"},"expectedVer":1}`, nil, false, 400, codeInvalidRequest, true},
		{"put value not object", "PUT", "/v1/data/request:1", `{"value":"x","indexes":{"collection":"r"}}`, nil, false, 400, codeInvalidRequest, true},
		{"put negative version", "PUT", "/v1/data/request:1", `{"value":{},"indexes":{"collection":"r"},"expectedVersion":-1}`, nil, false, 400, codeInvalidRequest, true},
		{"put storage down", "PUT", "/v1/data/request:1", validPut, store.ErrUnavailable, false, 503, codeStorageUnavailable, false},

		{"delete ok", "DELETE", "/v1/data/request:1", "", nil, false, 204, "", false},
		{"delete conditional", "DELETE", "/v1/data/request:1?expectedVersion=2", "", nil, false, 204, "", false},
		{"delete missing", "DELETE", "/v1/data/request:1", "", store.ErrNotFound, false, 404, codeNotFound, false},
		{"delete conflict", "DELETE", "/v1/data/request:1?expectedVersion=2", "", store.ErrVersionConflict, false, 409, codeVersionConflict, false},
		{"delete bad version", "DELETE", "/v1/data/request:1?expectedVersion=abc", "", nil, false, 400, codeInvalidRequest, true},

		{"query ok", "POST", "/v1/query", `{"collection":"resource","match":{"type":"WATER"},"limit":5}`, nil, false, 200, "", false},
		{"query no collection", "POST", "/v1/query", `{"match":{"type":"WATER"}}`, nil, false, 400, codeInvalidRequest, true},
		{"query bad limit", "POST", "/v1/query", `{"collection":"r","limit":5000}`, nil, false, 400, codeInvalidRequest, true},
		{"query storage down", "POST", "/v1/query", `{"collection":"r"}`, store.ErrUnavailable, false, 503, codeStorageUnavailable, false},

		{"healthz ignores storage", "GET", "/healthz", "", store.ErrUnavailable, false, 200, "", false},
		{"readyz ok", "GET", "/readyz", "", nil, false, 200, "", false},
		{"readyz storage down", "GET", "/readyz", "", store.ErrUnavailable, false, 503, codeStorageUnavailable, false},
		{"unknown path", "GET", "/v1/nope", "", nil, false, 404, "", true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fs := &fakeStore{err: tt.storeErr, created: tt.created}
			_, do := newTestHandler(fs)
			rr := do(tt.method, tt.path, tt.body)

			if rr.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d (body: %s)", rr.Code, tt.wantStatus, rr.Body)
			}
			if tt.wantCode != "" {
				var e errorBody
				if err := json.Unmarshal(rr.Body.Bytes(), &e); err != nil || e.Error != tt.wantCode || e.Message == "" || e.Timestamp == "" {
					t.Fatalf("error body = %s, want code %s with message and timestamp", rr.Body, tt.wantCode)
				}
			}
			if tt.wantNoStore && fs.calls != 0 {
				t.Errorf("store was called %d times; invalid requests must be rejected before forwarding", fs.calls)
			}
		})
	}
}

func TestSuccessfulResponsesCarryTheRecord(t *testing.T) {
	fs := &fakeStore{created: true}
	_, do := newTestHandler(fs)

	rr := do("PUT", "/v1/data/resource:tdr1yz:abc", `{"value":{"a":1},"indexes":{"collection":"resource"},"expectedVersion":0}`)
	var rec model.Record
	if err := json.Unmarshal(rr.Body.Bytes(), &rec); err != nil || rec.Key != "request:1" || rec.Version != 3 {
		t.Fatalf("response = %s", rr.Body)
	}
	if fs.gotKey != "resource:tdr1yz:abc" || fs.gotPut.ExpectedVersion == nil || *fs.gotPut.ExpectedVersion != 0 {
		t.Errorf("store got key %q expectedVersion %v; want expectedVersion 0 passed through", fs.gotKey, fs.gotPut.ExpectedVersion)
	}

	rr = do("POST", "/v1/query", `{"collection":"resource","geoCells":["tdr1yz","tdr1yy"]}`)
	var qr model.QueryResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &qr); err != nil || len(qr.Records) != 1 {
		t.Fatalf("query response = %s", rr.Body)
	}
	if len(fs.gotQuery.GeoCells) != 2 {
		t.Errorf("store got query %+v", fs.gotQuery)
	}

	do("DELETE", "/v1/data/request:1?expectedVersion=9", "")
	if fs.gotDelete == nil || *fs.gotDelete != 9 {
		t.Errorf("store got delete expectedVersion %v, want 9", fs.gotDelete)
	}
}

func TestMetrics(t *testing.T) {
	fs := &fakeStore{err: store.ErrVersionConflict}
	_, do := newTestHandler(fs)
	do("PUT", "/v1/data/request:1", validPut)
	fs.err = store.ErrUnavailable
	do("GET", "/v1/data/request:1", "")

	body := do("GET", "/metrics", "").Body.String()
	for _, want := range []string{
		"coordinator_version_conflicts_total 1",
		"coordinator_upstream_errors_total 1",
		`coordinator_http_requests_total{code="409",route="PUT /v1/data/{key}"} 1`,
		`coordinator_http_requests_total{code="503",route="GET /v1/data/{key}"} 1`,
		`coordinator_http_request_duration_seconds_count{route="GET /v1/data/{key}"} 1`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("/metrics missing %q", want)
		}
	}
}
