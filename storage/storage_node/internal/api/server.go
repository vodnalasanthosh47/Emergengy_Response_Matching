// Package api exposes the storage node's internal HTTP API
// (contracts/openapi/storage-node-api.yaml). Only the coordinator calls it.
package api

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/store"
)

// Store is what the handlers need from the storage layer. It is declared here, where
// it is used (a Go convention), so tests can pass a fake instead of MongoDB.
type Store interface {
	Get(ctx context.Context, key string) (model.Record, error)
	Put(ctx context.Context, key string, req model.PutRequest) (model.Record, bool, error)
	Delete(ctx context.Context, key string, expectedVersion *int64) (model.Record, error)
	Query(ctx context.Context, q model.QueryRequest) ([]model.Record, error)
	Ping(ctx context.Context) error

	// Replication and recovery (replica.go in the store package).
	ApplyReplica(ctx context.Context, rec model.Record) (bool, error)
	Repair(ctx context.Context, rec model.Record, localVersion int64) (bool, error)
	Purge(ctx context.Context, key string, localVersion int64) (bool, error)
	GetAny(ctx context.Context, key string) (model.Record, error)
	Since(ctx context.Context, since time.Time, afterKey string, limit int) (model.SyncPage, error)
	Stats(ctx context.Context) (model.Stats, error)
}

// Identity is this node's place in the cluster, reported by /internal/stats.
type Identity struct {
	NodeID  string
	ShardID int
}

const (
	defaultSyncLimit = 500
	maxSyncLimit     = 5000
)

type server struct {
	store     Store
	id        Identity
	log       *slog.Logger
	metrics   *Metrics
	opTimeout time.Duration
}

// NewHandler builds the HTTP routes. opTimeout bounds every database operation.
func NewHandler(st Store, id Identity, log *slog.Logger, metrics *Metrics, opTimeout time.Duration) http.Handler {
	s := &server{store: st, id: id, log: log, metrics: metrics, opTimeout: opTimeout}
	mux := http.NewServeMux()

	handle := func(pattern string, h http.HandlerFunc) {
		mux.Handle(pattern, instrument(pattern, metrics, log, h))
	}
	// Go 1.22+ patterns: method + path, {key} matches exactly one path segment.
	handle("GET /internal/data/{key}", s.getRecord)
	handle("PUT /internal/data/{key}", s.putRecord)
	handle("DELETE /internal/data/{key}", s.deleteRecord)
	handle("POST /internal/query", s.query)
	handle("PUT /internal/replica/{key}", s.applyReplica)
	handle("GET /internal/replica/{key}", s.getAny)
	handle("PUT /internal/repair/{key}", s.repair)
	handle("DELETE /internal/repair/{key}", s.purge)
	handle("GET /internal/sync", s.sync)
	handle("GET /internal/stats", s.stats)
	handle("GET /healthz", s.healthz)
	handle("GET /readyz", s.readyz)
	mux.Handle("GET /metrics", metrics.Handler())
	return mux
}

func (s *server) getRecord(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if err := model.ValidateKey(key); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()

	rec, err := s.store.Get(ctx, key)
	if err != nil {
		s.writeStoreError(w, key, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

func (s *server) putRecord(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if err := model.ValidateKey(key); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	var req model.PutRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	if err := req.Validate(); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()

	rec, created, err := s.store.Put(ctx, key, req)
	if err != nil {
		s.writeStoreError(w, key, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, rec)
}

func (s *server) deleteRecord(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if err := model.ValidateKey(key); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	expected, err := parseExpectedVersion(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()

	// The tombstone is returned (instead of 204) so the coordinator can replicate it.
	tomb, err := s.store.Delete(ctx, key, expected)
	if err != nil {
		s.writeStoreError(w, key, err)
		return
	}
	writeJSON(w, http.StatusOK, tomb)
}

// applyReplica stores a copy of a primary's write. 200 {"applied": false} means this
// node already holds a newer version, which is not an error.
func (s *server) applyReplica(w http.ResponseWriter, r *http.Request) {
	rec, ok := s.decodeReplica(w, r)
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()
	applied, err := s.store.ApplyReplica(ctx, rec)
	if err != nil {
		s.writeStoreError(w, rec.Key, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"applied": applied})
}

func (s *server) getAny(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if err := model.ValidateKey(key); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()
	rec, err := s.store.GetAny(ctx, key)
	if err != nil {
		s.writeStoreError(w, key, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

// repair overwrites a record during recovery, only if ?expectedLocalVersion= still
// matches. 200 {"applied": false} means the record changed meanwhile and was kept.
func (s *server) repair(w http.ResponseWriter, r *http.Request) {
	rec, ok := s.decodeReplica(w, r)
	if !ok {
		return
	}
	local, err := requiredVersion(r, "expectedLocalVersion")
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()
	applied, err := s.store.Repair(ctx, rec, local)
	if err != nil {
		s.writeStoreError(w, rec.Key, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"applied": applied})
}

func (s *server) purge(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if err := model.ValidateKey(key); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	local, err := requiredVersion(r, "expectedLocalVersion")
	if err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()
	applied, err := s.store.Purge(ctx, key, local)
	if err != nil {
		s.writeStoreError(w, key, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"applied": applied})
}

// sync serves one page of GET /internal/sync?since=&afterKey=&limit=.
func (s *server) sync(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var since time.Time
	if raw := q.Get("since"); raw != "" {
		t, err := time.Parse(time.RFC3339Nano, raw)
		if err != nil {
			writeError(w, http.StatusBadRequest, codeInvalidRequest, "since must be an RFC 3339 timestamp")
			return
		}
		since = t.UTC()
	}
	limit := defaultSyncLimit
	if raw := q.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > maxSyncLimit {
			writeError(w, http.StatusBadRequest, codeInvalidRequest, fmt.Sprintf("limit must be between 1 and %d", maxSyncLimit))
			return
		}
		limit = n
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()
	page, err := s.store.Since(ctx, since, q.Get("afterKey"), limit)
	if err != nil {
		s.writeStoreError(w, "", err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

func (s *server) stats(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()
	st, err := s.store.Stats(ctx)
	if err != nil {
		s.writeStoreError(w, "", err)
		return
	}
	st.NodeID, st.ShardID = s.id.NodeID, s.id.ShardID
	writeJSON(w, http.StatusOK, st)
}

// decodeReplica reads and validates a full record whose key must match the URL.
func (s *server) decodeReplica(w http.ResponseWriter, r *http.Request) (model.Record, bool) {
	var rec model.Record
	if err := decodeJSON(w, r, &rec); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return rec, false
	}
	if rec.Key != r.PathValue("key") {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, "body key does not match the URL")
		return rec, false
	}
	if err := rec.Validate(); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return rec, false
	}
	return rec, true
}

func (s *server) query(w http.ResponseWriter, r *http.Request) {
	var q model.QueryRequest
	if err := decodeJSON(w, r, &q); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	if err := q.Validate(); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()

	records, err := s.store.Query(ctx, q)
	if err != nil {
		s.writeStoreError(w, "", err)
		return
	}
	writeJSON(w, http.StatusOK, model.QueryResponse{Records: records})
}

// healthz is liveness: the process is running and serving HTTP. It deliberately does
// not check MongoDB — restarting this process would not fix a database outage.
func (s *server) healthz(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// readyz is readiness: we can actually serve data because MongoDB answers.
func (s *server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), s.opTimeout)
	defer cancel()
	if err := s.store.Ping(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, codeStorageUnavailable, "mongodb not reachable: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

// writeStoreError maps store errors onto the contract's status codes.
func (s *server) writeStoreError(w http.ResponseWriter, key string, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, codeNotFound, fmt.Sprintf("no record with key %q", key))
	case errors.Is(err, store.ErrVersionConflict):
		s.metrics.conflicts.Inc()
		writeError(w, http.StatusConflict, codeVersionConflict,
			fmt.Sprintf("record %q changed or already exists; re-read and retry", key))
	case errors.Is(err, store.ErrPreconditionFailed):
		writeError(w, http.StatusPreconditionFailed, codePreconditionFailed,
			fmt.Sprintf("expectedVersion given but record %q does not exist", key))
	case errors.Is(err, store.ErrInvalidValue):
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
	case errors.Is(err, store.ErrUnavailable):
		s.log.Warn("mongodb unavailable", "err", err)
		writeError(w, http.StatusServiceUnavailable, codeStorageUnavailable, "local database unavailable")
	default:
		s.log.Error("unexpected store error", "key", key, "err", err)
		writeError(w, http.StatusInternalServerError, codeInternalError, "internal error")
	}
}

// requiredVersion reads a mandatory version query parameter (>= 1).
func requiredVersion(r *http.Request, name string) (int64, error) {
	v, err := strconv.ParseInt(r.URL.Query().Get(name), 10, 64)
	if err != nil || v < 1 {
		return 0, fmt.Errorf("%s must be an integer >= 1", name)
	}
	return v, nil
}

// parseExpectedVersion reads the optional ?expectedVersion= query parameter of DELETE.
func parseExpectedVersion(r *http.Request) (*int64, error) {
	raw := r.URL.Query().Get("expectedVersion")
	if raw == "" {
		return nil, nil
	}
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || v < 1 {
		return nil, fmt.Errorf("expectedVersion must be an integer >= 1, got %q", raw)
	}
	return &v, nil
}
