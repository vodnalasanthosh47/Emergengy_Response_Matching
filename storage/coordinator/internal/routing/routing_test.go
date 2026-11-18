package routing

import (
	"errors"
	"fmt"
	"math/rand/v2"
	"testing"
)

const geohashAlphabet = "0123456789bcdefghjkmnpqrstuvwxyz"

func randomCell(r *rand.Rand, precision int) string {
	b := make([]byte, precision)
	for i := range b {
		b[i] = geohashAlphabet[r.IntN(len(geohashAlphabet))]
	}
	return string(b)
}

func randomUUID(r *rand.Rand) string {
	return fmt.Sprintf("%08x-%04x-4%03x-%04x-%012x", r.Uint32(), r.Uint32()&0xffff, r.Uint32()&0xfff,
		r.Uint32()&0xffff|0x8000, r.Uint64()&0xffffffffffff)
}

func TestParseKey(t *testing.T) {
	tests := []struct {
		key     string
		want    Key
		wantErr bool
	}{
		{key: "request:f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d",
			want: Key{Collection: "request", ID: "f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d", Token: "f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d"}},
		{key: "resource:tdr1yz:8a7c3e0e", want: Key{Collection: "resource", GeoCell: "tdr1yz", ID: "8a7c3e0e", Token: "tdr1yz"}},
		{key: "resource:t:x", want: Key{Collection: "resource", GeoCell: "t", ID: "x", Token: "t"}},
		{key: "notification:abc:def", want: Key{Token: "notification:abc:def"}}, // other collections: hashed whole
		{key: "plainkey", want: Key{Token: "plainkey"}},

		{key: "request:", wantErr: true},                 // empty ID
		{key: "request:tdr1y:f3b1", wantErr: true},       // requests are not keyed by cell
		{key: "resource:8a7c3e0e", wantErr: true},        // missing cell
		{key: "resource:tdr1yz:", wantErr: true},         // empty UUID
		{key: "resource:tdr1yz:a:b", wantErr: true},      // extra segment
		{key: "resource:smoke1:x", wantErr: true},        // "o" is not a geohash character
		{key: "resource:TDR1YZ:x", wantErr: true},        // geohashes are lower case
		{key: "resource:tdr1yzzzzzzzz:x", wantErr: true}, // precision > 12
		{key: "resource::x", wantErr: true},              // empty cell
		{key: "request:" + "a:b", wantErr: true},         // ":" inside the ID
	}
	for _, tt := range tests {
		got, err := ParseKey(tt.key)
		if tt.wantErr {
			if !errors.Is(err, ErrInvalidKey) {
				t.Errorf("ParseKey(%q) = %+v, %v; want ErrInvalidKey", tt.key, got, err)
			}
			continue
		}
		if err != nil || got != tt.want {
			t.Errorf("ParseKey(%q) = %+v, %v; want %+v", tt.key, got, err, tt.want)
		}
	}
}

// TestShardForIsStable pins the routing function. The expected values were computed
// independently (Python: zlib.crc32(token.encode()) % 3). If they ever change,
// existing data would be looked up on the wrong shard.
func TestShardForIsStable(t *testing.T) {
	for key, want := range map[string]int{
		// Requests: the hashed part is the request ID.
		"request:f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d": 0,
		"request:00000000-0000-0000-0000-000000000000": 1,
		"request:11111111-1111-1111-1111-111111111111": 2,
		// Resources: the hashed part is the geohash cell, whatever the UUID.
		"resource:tdr1yy:8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77": 0,
		"resource:tdr1y:8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77":  1,
		"resource:tdr1yz:8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77": 2,
		// Other keys: the whole key.
		"notification:x": 0,
	} {
		if got := ShardFor(key, 3); got != want {
			t.Errorf("ShardFor(%q) = %d, want %d", key, got, want)
		}
	}
	for cell, want := range map[string]int{"tdr1x": 0, "tdr1y": 1, "tdr1z": 2, "te7udq": 2} {
		if got := ShardForCell(cell, 3); got != want {
			t.Errorf("ShardForCell(%q) = %d, want %d", cell, got, want)
		}
	}
}

func TestResourcesOfOneCellShareAShard(t *testing.T) {
	r := rand.New(rand.NewPCG(7, 7))
	for range 200 {
		cell := randomCell(r, 6)
		want := ShardForCell(cell, 3)
		for range 20 {
			key := "resource:" + cell + ":" + randomUUID(r)
			if got := ShardFor(key, 3); got != want {
				t.Fatalf("%s on shard %d, but cell %s belongs to shard %d", key, got, cell, want)
			}
		}
	}
}

// TestRequestsSpreadEvenlyByID: requests are placed by their ID, so even a burst of
// requests from one place is spread over all shards.
func TestRequestsSpreadEvenlyByID(t *testing.T) {
	r := rand.New(rand.NewPCG(1, 2))
	const n = 30000
	counts := make([]int, 3)
	for range n {
		counts[ShardFor("request:"+randomUUID(r), 3)]++
	}
	assertEven(t, counts, n)
}

func TestCellsSpreadEvenlyOverThreeShards(t *testing.T) {
	r := rand.New(rand.NewPCG(3, 4))
	const n = 30000
	counts := make([]int, 3)
	for i := range n {
		counts[ShardFor(fmt.Sprintf("resource:%s:%d", randomCell(r, 6), i), 3)]++
	}
	assertEven(t, counts, n)
}

func assertEven(t *testing.T, counts []int, n int) {
	t.Helper()
	for shard, c := range counts {
		if c < n/3*90/100 || c > n/3*110/100 { // within ±10% of a third
			t.Errorf("shard %d got %d of %d keys (%v)", shard, c, n, counts)
		}
	}
}

func TestCheckIndexes(t *testing.T) {
	req, _ := ParseKey("request:r1")
	res, _ := ParseKey("resource:tdr1yz:u1")
	other, _ := ParseKey("notification:n1")
	tests := []struct {
		name    string
		key     Key
		indexes map[string]string
		ok      bool
	}{
		{"request ok", req, map[string]string{"collection": "request", "geoCell": "anything"}, true},
		{"request wrong collection", req, map[string]string{"collection": "resource"}, false},
		{"resource ok", res, map[string]string{"collection": "resource", "geoCell": "tdr1yz", "type": "WATER"}, true},
		{"resource wrong collection", res, map[string]string{"collection": "request", "geoCell": "tdr1yz"}, false},
		{"resource without geoCell", res, map[string]string{"collection": "resource"}, false},
		{"resource indexed in another cell", res, map[string]string{"collection": "resource", "geoCell": "tdr1yy"}, false},
		{"other collections are free", other, map[string]string{"collection": "whatever"}, true},
	}
	for _, tt := range tests {
		err := tt.key.CheckIndexes(tt.indexes)
		if tt.ok && err != nil {
			t.Errorf("%s: %v", tt.name, err)
		}
		if !tt.ok && !errors.Is(err, ErrIndexMismatch) {
			t.Errorf("%s: got %v, want ErrIndexMismatch", tt.name, err)
		}
	}
}
