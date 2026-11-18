package routing

import (
	"fmt"
	"math/rand/v2"
	"testing"
)

const geohashAlphabet = "0123456789bcdefghjkmnpqrstuvwxyz"

func randomCell(r *rand.Rand) string {
	b := make([]byte, 5) // precision 5, ≈ 4.9 km × 4.9 km
	for i := range b {
		b[i] = geohashAlphabet[r.IntN(len(geohashAlphabet))]
	}
	return string(b)
}

func TestToken(t *testing.T) {
	for key, want := range map[string]string{
		"request:tdr1y:3f2a":   "tdr1y",
		"resource:tdr1y:9b1c":  "tdr1y",
		"request:3f2a-uuid":    "3f2a-uuid",
		"email:ab12cd34:ab12x": "ab12cd34",
		"plainkey":             "plainkey",
	} {
		if got := Token(key); got != want {
			t.Errorf("Token(%q) = %q, want %q", key, got, want)
		}
	}
}

func TestSameCellSameShard(t *testing.T) {
	// Every collection created in one cell lands on one shard, whatever the uuid.
	want := ShardFor("request:tdr1y:a", 3)
	for _, k := range []string{"request:tdr1y:b", "resource:tdr1y:c", "volunteer:tdr1y:d"} {
		if got := ShardFor(k, 3); got != want {
			t.Errorf("ShardFor(%q) = %d, want %d", k, got, want)
		}
	}
}

func TestShardForIsStable(t *testing.T) {
	// Pinned values, computed independently (Python: zlib.crc32(cell) % 3). If these
	// change, existing data would be looked up on the wrong shard.
	for key, want := range map[string]int{
		"request:tdr1x:a":  0,
		"request:tdr1y:b":  1,
		"resource:tdr1z:c": 2,
		"request:u4pru:d":  0,
	} {
		if got := ShardFor(key, 3); got != want {
			t.Errorf("ShardFor(%q) = %d, want %d", key, got, want)
		}
	}
}

func TestCellsSpreadEvenlyOverThreeShards(t *testing.T) {
	r := rand.New(rand.NewPCG(1, 2))
	const n = 30000
	counts := make([]int, 3)
	for i := 0; i < n; i++ {
		counts[ShardFor(fmt.Sprintf("request:%s:%d", randomCell(r), i), 3)]++
	}
	for shard, c := range counts {
		if c < n/3*90/100 || c > n/3*110/100 { // within ±10% of a third
			t.Errorf("shard %d got %d of %d keys (%v)", shard, c, n, counts)
		}
	}
}
