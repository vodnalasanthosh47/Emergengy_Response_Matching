// Package routing maps a storage key to the shard that owns it.
//
// Keys have the form "{collection}:{homeCell}:{uuid}", where homeCell is the
// geohash of the entity's location when it was created. It is immutable, so a
// record never moves between shards, and every record created in the same cell
// lives on the same shard (cheap per-region locality). The shard is
//
//	crc32(homeCell) mod shards
//
// CRC32 is stable across processes and restarts, unlike Go's randomised map hash,
// so every coordinator instance and every restart routes a key the same way.
//
// Keys with fewer segments (e.g. "request:{uuid}") route on their second segment,
// and a key with no ":" routes on the whole key, so routing is total.
package routing

import (
	"hash/crc32"
	"strings"
)

// Token returns the part of key the shard is computed from: the homeCell segment.
func Token(key string) string {
	parts := strings.SplitN(key, ":", 3)
	if len(parts) >= 2 {
		return parts[1]
	}
	return key
}

// ShardFor returns the shard (0..shards-1) that owns key.
func ShardFor(key string, shards int) int {
	return int(crc32.ChecksumIEEE([]byte(Token(key))) % uint32(shards))
}
