// Package model contains the wire types and validation rules of the storage-tier
// contract (contracts/openapi/coordinator-api.yaml and storage-node-api.yaml).
//
// The coordinator module has its own copy of these types on purpose: each Go
// component is an independent module, and the OpenAPI files — not shared code —
// are the single source of truth.
package model

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"time"
)

const (
	DefaultQueryLimit = 50
	MaxQueryLimit     = 1000
	MaxGeoCells       = 100
)

var (
	// Keys look like "{collection}:{homeCell}:{uuid}" (the coordinator shards on the
	// homeCell geohash). Forbidding "/" keeps a key a single URL path segment.
	keyPattern = regexp.MustCompile(`^[A-Za-z0-9:_-]{1,256}$`)

	// Index attribute names become MongoDB field paths ("indexes.<name>"), so they must
	// not contain "." or "$" — otherwise a client could reach other fields or operators.
	indexNamePattern = regexp.MustCompile(`^[A-Za-z0-9_]{1,64}$`)
)

// Record is one stored item as returned to callers.
type Record struct {
	Key       string            `json:"key"`
	Value     json.RawMessage   `json:"value"` // opaque domain document; storage never interprets it
	Indexes   map[string]string `json:"indexes"`
	Version   int64             `json:"version"`
	UpdatedAt time.Time         `json:"updatedAt"`
	// Deleted marks a tombstone. Tombstones never appear on the data API; they travel
	// only on the replica and sync APIs so that deletes replicate like any other write.
	Deleted bool `json:"deleted,omitempty"`
}

// Validate checks a record received on the replica API (a copy of a primary's write).
func (r Record) Validate() error {
	if err := ValidateKey(r.Key); err != nil {
		return err
	}
	if r.Version < 1 {
		return errors.New("version must be >= 1")
	}
	if r.UpdatedAt.IsZero() {
		return errors.New("updatedAt is required")
	}
	if r.Deleted {
		return nil // a tombstone carries no value
	}
	if v := bytes.TrimSpace(r.Value); len(v) == 0 || v[0] != '{' {
		return errors.New("value must be a JSON object")
	}
	return validateIndexNames("indexes", r.Indexes)
}

// SyncPage is the body returned by GET /internal/sync: records (tombstones included)
// ordered by (updatedAt, key). Pass NextSince/NextKey back to get the following page.
type SyncPage struct {
	Records   []Record  `json:"records"`
	More      bool      `json:"more"`
	NextSince time.Time `json:"nextSince"`
	NextKey   string    `json:"nextKey"`
}

// Stats is the body returned by GET /internal/stats.
type Stats struct {
	NodeID       string    `json:"nodeId"`
	ShardID      int       `json:"shardId"`
	Records      int64     `json:"records"` // live records, tombstones excluded
	Tombstones   int64     `json:"tombstones"`
	MaxUpdatedAt time.Time `json:"maxUpdatedAt"`
}

// NodeInfo is what a node sends to POST /internal/nodes/register and /heartbeat.
type NodeInfo struct {
	NodeID       string    `json:"nodeId"`
	ShardID      int       `json:"shardId"`
	URL          string    `json:"url"`
	MaxUpdatedAt time.Time `json:"maxUpdatedAt"`
}

// PutRequest is the body of PUT /internal/data/{key}.
type PutRequest struct {
	Value   json.RawMessage   `json:"value"`
	Indexes map[string]string `json:"indexes"`
	// A pointer, because "absent" (unconditional write) and 0 ("must not exist")
	// mean different things and a plain int64 cannot tell them apart.
	ExpectedVersion *int64 `json:"expectedVersion,omitempty"`
}

// QueryRequest is the body of POST /internal/query.
type QueryRequest struct {
	Collection string            `json:"collection"`
	GeoCells   []string          `json:"geoCells,omitempty"`
	Match      map[string]string `json:"match,omitempty"`
	Limit      int               `json:"limit,omitempty"`
}

// QueryResponse is the body returned by POST /internal/query.
type QueryResponse struct {
	Records []Record `json:"records"`
}

// ValidateKey checks a storage key against the contract's pattern.
func ValidateKey(key string) error {
	if !keyPattern.MatchString(key) {
		return fmt.Errorf("invalid key %q: must match %s", key, keyPattern)
	}
	return nil
}

// ValidIndexName reports whether name may be used as an index attribute.
func ValidIndexName(name string) bool {
	return indexNamePattern.MatchString(name)
}

// Validate checks a PutRequest. It does not modify the request.
func (p PutRequest) Validate() error {
	if v := bytes.TrimSpace(p.Value); len(v) == 0 || v[0] != '{' {
		return errors.New("value must be a JSON object")
	}
	if err := validateIndexNames("indexes", p.Indexes); err != nil {
		return err
	}
	if p.Indexes["collection"] == "" {
		return errors.New("indexes.collection is required")
	}
	if p.ExpectedVersion != nil && *p.ExpectedVersion < 0 {
		return errors.New("expectedVersion must be >= 0")
	}
	return nil
}

// Validate checks a QueryRequest. It does not modify the request.
func (q QueryRequest) Validate() error {
	if q.Collection == "" {
		return errors.New("collection is required")
	}
	if len(q.GeoCells) > MaxGeoCells {
		return fmt.Errorf("at most %d geoCells allowed", MaxGeoCells)
	}
	for _, c := range q.GeoCells {
		if c == "" {
			return errors.New("geoCells must not contain empty strings")
		}
	}
	if err := validateIndexNames("match", q.Match); err != nil {
		return err
	}
	if q.Limit < 0 || q.Limit > MaxQueryLimit {
		return fmt.Errorf("limit must be between 1 and %d", MaxQueryLimit)
	}
	return nil
}

// EffectiveLimit returns the limit to apply, substituting the default for 0 (omitted).
func (q QueryRequest) EffectiveLimit() int {
	if q.Limit == 0 {
		return DefaultQueryLimit
	}
	return q.Limit
}

func validateIndexNames(field string, m map[string]string) error {
	for name := range m {
		if !ValidIndexName(name) {
			return fmt.Errorf("%s: invalid attribute name %q (letters, digits and _ only)", field, name)
		}
	}
	return nil
}
