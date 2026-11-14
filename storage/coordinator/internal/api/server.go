// Package api exposes the coordinator data API (contracts/openapi/coordinator-api.yaml),
// the application services' only entry point to storage.
//
// Handlers know nothing about storage nodes: they validate input, call the
// RecordStore, and map its errors to status codes. That is why sharding (CP2) and
// replication (CP3) will not change this package.
package api

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

type server struct {
	store   store.RecordStore
	log     *slog.Logger
	metrics *Metrics
	// readyTimeout bounds the /readyz check; data calls are bounded by the RecordStore.
	readyTimeout time.Duration
}

// NewHandler builds the HTTP routes.
func NewHandler(st store.RecordStore, log *slog.Logger, metrics *Metrics, readyTimeout time.Duration) http.Handler {
	s := &server{store: st, log: log, metrics: metrics, readyTimeout: readyTimeout}
	mux := http.NewServeMux()

	handle := func(pattern string, h http.HandlerFunc) {
		mux.Handle(pattern, instrument(pattern, metrics, log, h))
	}
	handle("GET /v1/data/{key}", s.getRecord)
	handle("PUT /v1/data/{key}", s.putRecord)
	handle("DELETE /v1/data/{key}", s.deleteRecord)
	handle("POST /v1/query", s.query)
	handle("GET /healthz", s.healthz)
	handle("GET /readyz", s.readyz)
	mux.Handle("GET /metrics", metrics.Handler())
	return mux
}

// Requests are validated here as well as on the storage node: rejecting bad input at
// the front door saves a network hop, and the node re-checks because it must never
// trust its callers.

func (s *server) getRecord(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if err := model.ValidateKey(key); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	rec, err := s.store.Get(r.Context(), key)
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
	rec, created, err := s.store.Put(r.Context(), key, req)
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
	if err := s.store.Delete(r.Context(), key, expected); err != nil {
		s.writeStoreError(w, key, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
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
	records, err := s.store.Query(r.Context(), q)
	if err != nil {
		s.writeStoreError(w, "", err)
		return
	}
	writeJSON(w, http.StatusOK, model.QueryResponse{Records: records})
}

// healthz is liveness only: restarting the coordinator would not fix a storage outage.
func (s *server) healthz(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// readyz is readiness: the storage tier behind us can serve requests.
func (s *server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), s.readyTimeout)
	defer cancel()
	if err := s.store.Ready(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, codeStorageUnavailable, "storage not ready: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

// writeStoreError maps RecordStore errors onto the contract's status codes. Because the
// forwarder turns the node's 404/409/412 back into these same errors, the services see
// exactly the status code the storage node decided on.
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
	case errors.Is(err, store.ErrInvalidRequest):
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
	case errors.Is(err, store.ErrUnavailable):
		s.metrics.upstreamErrors.Inc()
		s.log.Warn("storage unavailable", "key", key, "err", err)
		writeError(w, http.StatusServiceUnavailable, codeStorageUnavailable, "storage tier unavailable, retry later")
	default:
		s.log.Error("unexpected store error", "key", key, "err", err)
		writeError(w, http.StatusInternalServerError, codeInternalError, "internal error")
	}
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
