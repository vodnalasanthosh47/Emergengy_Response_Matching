// Package routing decides which shard owns a storage key.
//
// Each collection has its own partitioning rule, chosen for how it is looked up:
//
//		request:{requestId}             shard = crc32(requestId) mod shards
//		resource:{geoCell}:{uuid}       shard = crc32(geoCell)   mod shards
//
//	  - Requests are almost always read by their ID, so they are spread by a hash of the
//	    ID (the PDF's "hash(request_id) mod 3"). This gives an even spread even when a
//	    disaster puts thousands of requests in the same few cells.
//	  - Resources are searched by location ("water near cell C"). Sharding them by their
//	    geohash cell keeps every resource of a cell on one shard, so a proximity query
//	    only has to ask the shards that own the cells it covers (see store.ShardedStore).
//
// The cell is part of the resource key, so the shard is still derived from the key
// alone and a GET by ID needs no extra information. The cell must never change for a
// key: moving a resource means re-keying it (delete + create).
//
// Keys of any other collection are hashed as a whole, so routing is total.
//
// CRC32 is stable across processes, restarts and languages (unlike Go's randomised
// map hash), so every coordinator instance and every restart routes a key the same way.
package routing

import (
	"errors"
	"fmt"
	"hash/crc32"
	"regexp"
	"strings"
)

// Collections with their own partitioning rule.
const (
	CollectionRequest  = "request"
	CollectionResource = "resource"
)

var (
	// ErrInvalidKey: a request or resource key does not have its collection's shape.
	ErrInvalidKey = errors.New("invalid key")
	// ErrIndexMismatch: a record's indexes contradict its key, which would make
	// geo-routed queries miss it.
	ErrIndexMismatch = errors.New("indexes do not match the key")
)

var (
	// The geohash base32 alphabet (no a, i, l, o). Precision 1–12.
	geohashPattern = regexp.MustCompile(`^[0123456789bcdefghjkmnpqrstuvwxyz]{1,12}$`)
	// IDs (request IDs, resource UUIDs) are one key segment: no ":".
	idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
)

// Key is a parsed storage key.
type Key struct {
	Collection string // CollectionRequest, CollectionResource, or "" for any other key
	ID         string // the request ID or resource UUID
	GeoCell    string // resources only: the geohash cell the resource is stored under
	Token      string // the part that is hashed to pick the shard
}

// ParseKey checks a key against its collection's shape and returns its parts. Keys of
// other collections are accepted as they are and hashed whole.
func ParseKey(key string) (Key, error) {
	parts := strings.Split(key, ":")
	switch parts[0] {
	case CollectionRequest:
		if len(parts) != 2 || !idPattern.MatchString(parts[1]) {
			return Key{}, fmt.Errorf("%w: request keys look like request:{requestId}, got %q", ErrInvalidKey, key)
		}
		return Key{Collection: CollectionRequest, ID: parts[1], Token: parts[1]}, nil
	case CollectionResource:
		if len(parts) != 3 || !geohashPattern.MatchString(parts[1]) || !idPattern.MatchString(parts[2]) {
			return Key{}, fmt.Errorf("%w: resource keys look like resource:{geohash}:{uuid}, got %q", ErrInvalidKey, key)
		}
		return Key{Collection: CollectionResource, GeoCell: parts[1], ID: parts[2], Token: parts[1]}, nil
	default:
		return Key{Token: key}, nil
	}
}

// CheckIndexes reports whether a record's index attributes agree with its key:
// request and resource records must carry their own collection, and a resource's
// indexes.geoCell must be the cell in its key. The coordinator routes geo queries to
// the shards owning the queried cells, so a resource indexed under a different cell
// than the one it is stored under would silently disappear from those queries.
func (k Key) CheckIndexes(indexes map[string]string) error {
	if k.Collection == "" {
		return nil
	}
	if got := indexes["collection"]; got != k.Collection {
		return fmt.Errorf("%w: key is a %s but indexes.collection is %q", ErrIndexMismatch, k.Collection, got)
	}
	if k.Collection == CollectionResource && indexes["geoCell"] != k.GeoCell {
		return fmt.Errorf("%w: resource key is in cell %q but indexes.geoCell is %q (moving a resource means re-keying it)",
			ErrIndexMismatch, k.GeoCell, indexes["geoCell"])
	}
	return nil
}

// ShardFor returns the shard (0..shards-1) that owns key. It is total: a key that does
// not parse is hashed whole (the API rejects such keys before they get here).
func ShardFor(key string, shards int) int {
	token := key
	if k, err := ParseKey(key); err == nil {
		token = k.Token
	}
	return ShardForToken(token, shards)
}

// ShardForCell returns the shard that owns the resources of a geohash cell.
func ShardForCell(cell string, shards int) int { return ShardForToken(cell, shards) }

// ShardForToken is the hash itself: crc32(token) mod shards.
func ShardForToken(token string, shards int) int {
	return int(crc32.ChecksumIEEE([]byte(token)) % uint32(shards))
}
