package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/store"
)

// fakeStore returns whatever error is configured and records what it was called with.
type fakeStore struct {
	err       error
	created   bool
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
	f.gotKey = key
	return sampleRecord, f.err
}
func (f *fakeStore) Put(_ context.Context, key string, req model.PutRequest) (model.Record, bool, error) {
	f.gotKey, f.gotPut = key, req
	return sampleRecord, f.created, f.err
}
func (f *fakeStore) Delete(_ context.Context, key string, ev *int64) error {
	f.gotKey, f.gotDelete = key, ev
	return f.err
}
func (f *fakeStore) Query(_ context.Context, q model.QueryRequest) ([]model.Record, error) {
	f.gotQuery = q
	return []model.Record{sampleRecord}, f.err
}
func (f *fakeStore) Ping(context.Context) error { return f.err }

const validPut = `{"value":{"a":1},"indexes":{"collection":"request"}}`

func TestHandlers(t *testing.T) {
	tests := []struct {
		name       string
		method     string
		path       string
		body       string
		storeErr   error
		created    bool
		wantStatus int
		wantCode   string // error code in the JSON body, if any
	}{
		{"get ok", "GET", "/internal/data/request:1", "", nil, false, 200, ""},
		{"get missing", "GET", "/internal/data/request:1", "", store.ErrNotFound, false, 404, codeNotFound},
		{"get bad key", "GET", "/internal/data/bad$key", "", nil, false, 400, codeInvalidRequest},
		{"get mongo down", "GET", "/internal/data/request:1", "", store.ErrUnavailable, false, 503, codeStorageUnavailable},
		{"get unexpected error", "GET", "/internal/data/request:1", "", errors.New("boom"), false, 500, codeInternalError},

		{"put created", "PUT", "/internal/data/request:1", validPut, nil, true, 201, ""},
		{"put replaced", "PUT", "/internal/data/request:1", validPut, nil, false, 200, ""},
		{"put conflict", "PUT", "/internal/data/request:1", validPut, store.ErrVersionConflict, false, 409, codeVersionConflict},
		{"put precondition", "PUT", "/internal/data/request:1", validPut, store.ErrPreconditionFailed, false, 412, codePreconditionFailed},
		{"put bad value", "PUT", "/internal/data/request:1", validPut, store.ErrInvalidValue, false, 400, codeInvalidRequest},
		{"put malformed json", "PUT", "/internal/data/request:1", `{"value":`, nil, false, 400, codeInvalidRequest},
		// (Go's encoding/json matches names case-insensitively, so "expectedversion" would
		// still bind correctly; a misspelling is what DisallowUnknownFields must catch.)
		{"put unknown field (typo)", "PUT", "/internal/data/request:1",
			`{"value":{},"indexes":{"collection":"r"},"expectedVer":3}`, nil, false, 400, codeInvalidRequest},
		{"put two objects", "PUT", "/internal/data/request:1", validPut + validPut, nil, false, 400, codeInvalidRequest},
		{"put missing collection", "PUT", "/internal/data/request:1", `{"value":{},"indexes":{}}`, nil, false, 400, codeInvalidRequest},
		{"put body too large", "PUT", "/internal/data/request:1",
			`{"value":{"x":"` + strings.Repeat("a", maxBodyBytes) + `"},"indexes":{"collection":"r"}}`, nil, false, 400, codeInvalidRequest},

		{"delete ok", "DELETE", "/internal/data/request:1", "", nil, false, 204, ""},
		{"delete conditional ok", "DELETE", "/internal/data/request:1?expectedVersion=3", "", nil, false, 204, ""},
		{"delete bad version", "DELETE", "/internal/data/request:1?expectedVersion=0", "", nil, false, 400, codeInvalidRequest},
		{"delete non-numeric version", "DELETE", "/internal/data/request:1?expectedVersion=x", "", nil, false, 400, codeInvalidRequest},
		{"delete conflict", "DELETE", "/internal/data/request:1?expectedVersion=3", "", store.ErrVersionConflict, false, 409, codeVersionConflict},

		{"query ok", "POST", "/internal/query", `{"collection":"resource","geoCells":["tdr1yz"],"match":{"type":"WATER"}}`, nil, false, 200, ""},
		{"query missing collection", "POST", "/internal/query", `{}`, nil, false, 400, codeInvalidRequest},
		{"query injection attempt", "POST", "/internal/query", `{"collection":"r","match":{"$where":"1"}}`, nil, false, 400, codeInvalidRequest},
		{"query mongo down", "POST", "/internal/query", `{"collection":"r"}`, store.ErrUnavailable, false, 503, codeStorageUnavailable},

		{"healthz ignores mongo", "GET", "/healthz", "", store.ErrUnavailable, false, 200, ""},
		{"readyz ok", "GET", "/readyz", "", nil, false, 200, ""},
		{"readyz mongo down", "GET", "/readyz", "", store.ErrUnavailable, false, 503, codeStorageUnavailable},
		{"wrong method", "POST", "/internal/data/request:1", "{}", nil, false, 405, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fs := &fakeStore{err: tt.storeErr, created: tt.created}
			h := NewHandler(fs, slog.New(slog.NewTextHandler(io.Discard, nil)), NewMetrics("test"), time.Second)

			req := httptest.NewRequest(tt.method, tt.path, strings.NewReader(tt.body))
			rr := httptest.NewRecorder()
			h.ServeHTTP(rr, req)

			if rr.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d (body: %s)", rr.Code, tt.wantStatus, rr.Body)
			}
			if tt.wantCode != "" {
				var e errorBody
				if err := json.Unmarshal(rr.Body.Bytes(), &e); err != nil || e.Error != tt.wantCode || e.Message == "" || e.Timestamp == "" {
					t.Fatalf("error body = %s, want code %s with message and timestamp", rr.Body, tt.wantCode)
				}
			}
		})
	}
}

func TestPutPassesExpectedVersionThrough(t *testing.T) {
	for _, tc := range []struct {
		body string
		want string
	}{
		{`{"value":{},"indexes":{"collection":"r"}}`, "<nil>"},
		{`{"value":{},"indexes":{"collection":"r"},"expectedVersion":0}`, "0"},
		{`{"value":{},"indexes":{"collection":"r"},"expectedVersion":7}`, "7"},
	} {
		fs := &fakeStore{}
		h := NewHandler(fs, slog.New(slog.NewTextHandler(io.Discard, nil)), NewMetrics("test"), time.Second)
		h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("PUT", "/internal/data/resource:tdr1yz:x", strings.NewReader(tc.body)))

		got := "<nil>"
		if fs.gotPut.ExpectedVersion != nil {
			got = fmt.Sprint(*fs.gotPut.ExpectedVersion)
		}
		if fs.gotKey != "resource:tdr1yz:x" || got != tc.want {
			t.Errorf("body %s: store got key %q expectedVersion %s, want resource:tdr1yz:x / %s", tc.body, fs.gotKey, got, tc.want)
		}
	}
}

func TestMetricsEndpointCountsConflicts(t *testing.T) {
	fs := &fakeStore{err: store.ErrVersionConflict}
	h := NewHandler(fs, slog.New(slog.NewTextHandler(io.Discard, nil)), NewMetrics("storage_node"), time.Second)
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("PUT", "/internal/data/request:1", strings.NewReader(validPut)))

	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest("GET", "/metrics", nil))
	body := rr.Body.String()
	for _, want := range []string{
		"storage_node_version_conflicts_total 1",
		`storage_node_http_requests_total{code="409",route="PUT /internal/data/{key}"} 1`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("/metrics missing %q", want)
		}
	}
	if rr.Code != http.StatusOK {
		t.Errorf("/metrics status = %d", rr.Code)
	}
}
