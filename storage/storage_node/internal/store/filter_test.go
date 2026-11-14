package store

import (
	"reflect"
	"testing"

	"go.mongodb.org/mongo-driver/v2/bson"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

func TestBuildFilter(t *testing.T) {
	tests := []struct {
		name string
		q    model.QueryRequest
		want bson.M
	}{
		{
			name: "collection only",
			q:    model.QueryRequest{Collection: "request"},
			want: bson.M{"indexes.collection": "request"},
		},
		{
			name: "empty geoCells means any cell",
			q:    model.QueryRequest{Collection: "resource", GeoCells: []string{}},
			want: bson.M{"indexes.collection": "resource"},
		},
		{
			name: "cells and match",
			q: model.QueryRequest{
				Collection: "resource",
				GeoCells:   []string{"tdr1yz", "tdr1yy"},
				Match:      map[string]string{"type": "WATER", "status": "AVAILABLE"},
			},
			want: bson.M{
				"indexes.collection": "resource",
				"indexes.geoCell":    bson.M{"$in": []string{"tdr1yz", "tdr1yy"}},
				"indexes.type":       "WATER",
				"indexes.status":     "AVAILABLE",
			},
		},
		{
			name: "geoCell in match without geoCells",
			q:    model.QueryRequest{Collection: "resource", Match: map[string]string{"geoCell": "tdr1yz"}},
			want: bson.M{"indexes.collection": "resource", "indexes.geoCell": "tdr1yz"},
		},
		{
			name: "geoCell in both match and geoCells",
			q: model.QueryRequest{
				Collection: "resource",
				GeoCells:   []string{"tdr1yz", "tdr1yy"},
				Match:      map[string]string{"geoCell": "tdr1yy"},
			},
			want: bson.M{
				"indexes.collection": "resource",
				"indexes.geoCell":    bson.M{"$in": []string{"tdr1yz", "tdr1yy"}, "$eq": "tdr1yy"},
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := BuildFilter(tt.q)
			if err != nil {
				t.Fatalf("BuildFilter() error = %v", err)
			}
			if !reflect.DeepEqual(got, tt.want) {
				t.Errorf("BuildFilter() =\n  %v\nwant\n  %v", got, tt.want)
			}
		})
	}
}

func TestBuildFilterRejectsUnsafeNames(t *testing.T) {
	for _, name := range []string{"$where", "a.b", "value", ""} {
		q := model.QueryRequest{Collection: "resource", Match: map[string]string{name: "x"}}
		_, err := BuildFilter(q)
		// "value" is a syntactically valid name — it only ever addresses indexes.value,
		// never the top-level value document, so it is allowed.
		if name == "value" {
			if err != nil {
				t.Errorf("BuildFilter(%q) unexpected error: %v", name, err)
			}
			continue
		}
		if err == nil {
			t.Errorf("BuildFilter(%q) expected an error", name)
		}
	}
}
