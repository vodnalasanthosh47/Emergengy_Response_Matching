package store

import (
	"fmt"

	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

// BuildFilter translates a QueryRequest into a MongoDB filter on the "indexes.*" fields.
//
// Kept as a pure function (no database) so it can be unit-tested directly.
func BuildFilter(q model.QueryRequest) (bson.M, error) {
	filter := bson.M{"indexes.collection": q.Collection, "deleted": notDeleted()}

	// Empty and omitted geoCells both mean "any cell" (see the contract).
	if len(q.GeoCells) > 0 {
		filter["indexes.geoCell"] = bson.M{"$in": q.GeoCells}
	}

	for name, value := range q.Match {
		// Defence in depth: the API layer validates names too, but this function builds
		// field paths from them, so it must never trust its input. Values are plain strings,
		// so they can only ever be compared for equality — never act as operators like $ne.
		if !model.ValidIndexName(name) {
			return nil, fmt.Errorf("invalid match attribute name %q", name)
		}
		if name == "geoCell" && len(q.GeoCells) > 0 {
			// Both forms given: require the cell to be in the list AND equal to the value.
			filter["indexes.geoCell"] = bson.M{"$in": q.GeoCells, "$eq": value}
			continue
		}
		filter["indexes."+name] = value
	}
	return filter, nil
}
