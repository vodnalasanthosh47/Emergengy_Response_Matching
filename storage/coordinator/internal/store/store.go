// Package store defines how the coordinator reaches the storage tier.
//
// The HTTP handlers depend only on the RecordStore interface, implemented by
// ShardedStore: it routes each key to its shard (geohash of the key's homeCell),
// writes through the shard's primary, replicates to the shard's replicas, and reads
// from a replica when the primary is down. Forwarder is the HTTP client for one
// storage node, and Pool keeps one Forwarder per node.
package store

import (
	"context"
	"errors"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
)

// Errors every RecordStore implementation returns, mapped to HTTP codes by the API layer.
var (
	ErrNotFound           = errors.New("record not found")                                // 404
	ErrVersionConflict    = errors.New("version conflict")                                // 409
	ErrPreconditionFailed = errors.New("expectedVersion given but record does not exist") // 412
	ErrInvalidRequest     = errors.New("invalid request")                                 // 400
	ErrUnavailable        = errors.New("storage unavailable")                             // 503
)

// RecordStore is the coordinator's view of storage. It mirrors the data API exactly.
//
// Note the coordinator does not implement versioning itself: the atomic
// compare-and-set happens on the storage node that owns the key, and implementations
// simply pass expectedVersion through.
type RecordStore interface {
	Get(ctx context.Context, key string) (model.Record, error)
	// Put returns created=true when the record did not exist before.
	Put(ctx context.Context, key string, req model.PutRequest) (rec model.Record, created bool, err error)
	Delete(ctx context.Context, key string, expectedVersion *int64) error
	Query(ctx context.Context, q model.QueryRequest) ([]model.Record, error)
	// Ready reports whether the storage tier can currently serve requests.
	Ready(ctx context.Context) error
}
