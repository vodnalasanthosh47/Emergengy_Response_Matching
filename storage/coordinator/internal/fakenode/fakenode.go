// Package fakenode is an in-memory storage node speaking the storage-node HTTP API
// (contracts/openapi/storage-node-api.yaml), for testing the coordinator's routing,
// replication, failover and recovery without MongoDB. It follows the same version
// rules as the real node; the real node's own tests cover MongoDB.
package fakenode

import (
	"cmp"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
)

// Node is one fake storage node.
type Node struct {
	ID, URL string
	// Down makes every call fail with 503, like a node whose process is gone.
	Down atomic.Bool
	// PutDelay and ReplicaDelay (nanoseconds) slow down PUT /internal/data and
	// PUT /internal/replica before they are applied, to stage races in tests.
	PutDelay, ReplicaDelay atomic.Int64
	// Queries counts POST /internal/query calls this node answered.
	Queries atomic.Int64

	srv  *httptest.Server
	mu   sync.Mutex
	recs map[string]model.Record
	tick int64
}

// Start starts a fake node; it is closed when the test ends.
func Start(t interface{ Cleanup(func()) }, id string) *Node {
	n := &Node{ID: id, recs: map[string]model.Record{}}
	n.srv = httptest.NewServer(n.handler())
	n.URL = n.srv.URL
	t.Cleanup(n.srv.Close)
	return n
}

// Records returns a copy of every record, tombstones included, sorted by key.
func (n *Node) Records() []model.Record {
	n.mu.Lock()
	defer n.mu.Unlock()
	out := make([]model.Record, 0, len(n.recs))
	for _, r := range n.recs {
		out = append(out, r)
	}
	slices.SortFunc(out, func(a, b model.Record) int { return cmp.Compare(a.Key, b.Key) })
	return out
}

// Live returns the number of live (non-deleted) records.
func (n *Node) Live() int {
	c := 0
	for _, r := range n.Records() {
		if !r.Deleted {
			c++
		}
	}
	return c
}

// Seed stores rec directly, bypassing the API (e.g. to plant an unreplicated write).
func (n *Node) Seed(rec model.Record) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.recs[rec.Key] = rec
}

// now returns strictly increasing millisecond timestamps, so ordering is stable.
func (n *Node) now() time.Time {
	n.tick++
	return time.Now().UTC().Truncate(time.Millisecond).Add(time.Duration(n.tick) * time.Millisecond)
}

func (n *Node) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /internal/data/{key}", func(w http.ResponseWriter, r *http.Request) {
		n.mu.Lock()
		defer n.mu.Unlock()
		rec, ok := n.recs[r.PathValue("key")]
		if !ok || rec.Deleted {
			reply(w, 404, nil)
			return
		}
		reply(w, 200, rec)
	})
	mux.HandleFunc("PUT /internal/data/{key}", func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(time.Duration(n.PutDelay.Load()))
		var req model.PutRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		key := r.PathValue("key")
		n.mu.Lock()
		defer n.mu.Unlock()
		cur, exists := n.recs[key]
		live := exists && !cur.Deleted
		switch {
		case req.ExpectedVersion == nil:
		case *req.ExpectedVersion == 0 && live:
			reply(w, 409, nil)
			return
		case *req.ExpectedVersion > 0 && !live:
			reply(w, 412, nil)
			return
		case *req.ExpectedVersion > 0 && cur.Version != *req.ExpectedVersion:
			reply(w, 409, nil)
			return
		}
		rec := model.Record{Key: key, Value: req.Value, Indexes: req.Indexes, Version: cur.Version + 1, UpdatedAt: n.now()}
		n.recs[key] = rec
		status := 200
		if !live {
			status = 201
		}
		reply(w, status, rec)
	})
	mux.HandleFunc("DELETE /internal/data/{key}", func(w http.ResponseWriter, r *http.Request) {
		key := r.PathValue("key")
		n.mu.Lock()
		defer n.mu.Unlock()
		cur, ok := n.recs[key]
		if !ok || cur.Deleted {
			reply(w, 404, nil)
			return
		}
		if ev := r.URL.Query().Get("expectedVersion"); ev != "" && ev != strconv.FormatInt(cur.Version, 10) {
			reply(w, 409, nil)
			return
		}
		tomb := model.Record{Key: key, Value: json.RawMessage(`{}`), Indexes: cur.Indexes, Version: cur.Version + 1, UpdatedAt: n.now(), Deleted: true}
		n.recs[key] = tomb
		reply(w, 200, tomb)
	})
	mux.HandleFunc("PUT /internal/replica/{key}", func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(time.Duration(n.ReplicaDelay.Load()))
		var rec model.Record
		_ = json.NewDecoder(r.Body).Decode(&rec)
		n.mu.Lock()
		defer n.mu.Unlock()
		cur, ok := n.recs[rec.Key]
		applied := !ok || rec.Version >= cur.Version
		if applied {
			n.recs[rec.Key] = rec
		}
		reply(w, 200, map[string]bool{"applied": applied})
	})
	mux.HandleFunc("GET /internal/replica/{key}", func(w http.ResponseWriter, r *http.Request) {
		n.mu.Lock()
		defer n.mu.Unlock()
		rec, ok := n.recs[r.PathValue("key")]
		if !ok {
			reply(w, 404, nil)
			return
		}
		reply(w, 200, rec)
	})
	mux.HandleFunc("PUT /internal/repair/{key}", func(w http.ResponseWriter, r *http.Request) {
		var rec model.Record
		_ = json.NewDecoder(r.Body).Decode(&rec)
		local, _ := strconv.ParseInt(r.URL.Query().Get("expectedLocalVersion"), 10, 64)
		n.mu.Lock()
		defer n.mu.Unlock()
		cur, ok := n.recs[rec.Key]
		applied := ok && cur.Version == local
		if applied {
			n.recs[rec.Key] = rec
		}
		reply(w, 200, map[string]bool{"applied": applied})
	})
	mux.HandleFunc("DELETE /internal/repair/{key}", func(w http.ResponseWriter, r *http.Request) {
		key := r.PathValue("key")
		local, _ := strconv.ParseInt(r.URL.Query().Get("expectedLocalVersion"), 10, 64)
		n.mu.Lock()
		defer n.mu.Unlock()
		cur, ok := n.recs[key]
		applied := ok && cur.Version == local
		if applied {
			delete(n.recs, key)
		}
		reply(w, 200, map[string]bool{"applied": applied})
	})
	mux.HandleFunc("GET /internal/sync", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		var since time.Time
		if s := q.Get("since"); s != "" {
			since, _ = time.Parse(time.RFC3339Nano, s)
		}
		after := q.Get("afterKey")
		limit, _ := strconv.Atoi(q.Get("limit"))
		all := n.Records()
		slices.SortFunc(all, func(a, b model.Record) int {
			if c := a.UpdatedAt.Compare(b.UpdatedAt); c != 0 {
				return c
			}
			return cmp.Compare(a.Key, b.Key)
		})
		page := model.SyncPage{Records: []model.Record{}, NextSince: since, NextKey: after}
		for _, rec := range all {
			if rec.UpdatedAt.Before(since) || (rec.UpdatedAt.Equal(since) && rec.Key <= after) {
				continue
			}
			if len(page.Records) == limit {
				page.More = true
				break
			}
			page.Records = append(page.Records, rec)
			page.NextSince, page.NextKey = rec.UpdatedAt, rec.Key
		}
		reply(w, 200, page)
	})
	mux.HandleFunc("POST /internal/query", func(w http.ResponseWriter, r *http.Request) {
		n.Queries.Add(1)
		var q model.QueryRequest
		_ = json.NewDecoder(r.Body).Decode(&q)
		out := []model.Record{}
		for _, rec := range n.Records() {
			if rec.Deleted || rec.Indexes["collection"] != q.Collection {
				continue
			}
			if len(q.GeoCells) > 0 && !slices.Contains(q.GeoCells, rec.Indexes["geoCell"]) {
				continue
			}
			ok := true
			for k, v := range q.Match {
				ok = ok && rec.Indexes[k] == v
			}
			if ok && len(out) < q.EffectiveLimit() {
				out = append(out, rec)
			}
		}
		reply(w, 200, model.QueryResponse{Records: out})
	})
	mux.HandleFunc("GET /internal/stats", func(w http.ResponseWriter, r *http.Request) {
		st := model.Stats{NodeID: n.ID}
		for _, rec := range n.Records() {
			if rec.Deleted {
				st.Tombstones++
			} else {
				st.Records++
			}
		}
		reply(w, 200, st)
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) { reply(w, 200, map[string]string{"status": "ready"}) })

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if n.Down.Load() {
			reply(w, 503, nil)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func reply(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if body == nil {
		body = map[string]string{"error": http.StatusText(status), "message": http.StatusText(status)}
	}
	_ = json.NewEncoder(w).Encode(body)
}
