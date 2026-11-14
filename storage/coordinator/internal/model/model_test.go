package model

import (
	"encoding/json"
	"strings"
	"testing"
)

func ptr(v int64) *int64 { return &v }

func TestValidateKey(t *testing.T) {
	tests := []struct {
		key     string
		wantErr bool
	}{
		{"request:3f1c2e4a-1b2c-4d5e-8f90-123456789abc", false},
		{"resource:tdr1yz:8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77", false},
		{"a", false},
		{"", true},
		{"has/slash", true},
		{"has space", true},
		{"dollar$", true},
		{strings.Repeat("k", 256), false},
		{strings.Repeat("k", 257), true},
	}
	for _, tt := range tests {
		err := ValidateKey(tt.key)
		if (err != nil) != tt.wantErr {
			t.Errorf("ValidateKey(%q) error = %v, wantErr %v", tt.key, err, tt.wantErr)
		}
	}
}

func TestPutRequestValidate(t *testing.T) {
	valid := map[string]string{"collection": "resource", "geoCell": "tdr1yz"}
	tests := []struct {
		name    string
		req     PutRequest
		wantErr string
	}{
		{"valid unconditional", PutRequest{Value: json.RawMessage(`{"a":1}`), Indexes: valid}, ""},
		{"valid create-only", PutRequest{Value: json.RawMessage(`{}`), Indexes: valid, ExpectedVersion: ptr(0)}, ""},
		{"value with leading whitespace", PutRequest{Value: json.RawMessage(" \n{}"), Indexes: valid}, ""},
		{"missing value", PutRequest{Indexes: valid}, "value must be a JSON object"},
		{"null value", PutRequest{Value: json.RawMessage(`null`), Indexes: valid}, "value must be a JSON object"},
		{"array value", PutRequest{Value: json.RawMessage(`[1]`), Indexes: valid}, "value must be a JSON object"},
		{"missing indexes", PutRequest{Value: json.RawMessage(`{}`)}, "indexes.collection is required"},
		{"empty collection", PutRequest{Value: json.RawMessage(`{}`), Indexes: map[string]string{"collection": ""}}, "indexes.collection is required"},
		{"dotted index name", PutRequest{Value: json.RawMessage(`{}`), Indexes: map[string]string{"collection": "x", "a.b": "1"}}, "invalid attribute name"},
		{"operator index name", PutRequest{Value: json.RawMessage(`{}`), Indexes: map[string]string{"collection": "x", "$where": "1"}}, "invalid attribute name"},
		{"negative expectedVersion", PutRequest{Value: json.RawMessage(`{}`), Indexes: valid, ExpectedVersion: ptr(-1)}, "expectedVersion must be >= 0"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.req.Validate()
			checkErr(t, err, tt.wantErr)
		})
	}
}

func TestQueryRequestValidate(t *testing.T) {
	tooManyCells := make([]string, MaxGeoCells+1)
	for i := range tooManyCells {
		tooManyCells[i] = "c"
	}
	tests := []struct {
		name    string
		req     QueryRequest
		wantErr string
	}{
		{"collection only", QueryRequest{Collection: "resource"}, ""},
		{"full", QueryRequest{Collection: "resource", GeoCells: []string{"tdr1yz"}, Match: map[string]string{"type": "WATER"}, Limit: 10}, ""},
		{"max limit", QueryRequest{Collection: "resource", Limit: MaxQueryLimit}, ""},
		{"missing collection", QueryRequest{}, "collection is required"},
		{"limit too big", QueryRequest{Collection: "r", Limit: MaxQueryLimit + 1}, "limit must be"},
		{"negative limit", QueryRequest{Collection: "r", Limit: -1}, "limit must be"},
		{"too many cells", QueryRequest{Collection: "r", GeoCells: tooManyCells}, "at most"},
		{"empty cell", QueryRequest{Collection: "r", GeoCells: []string{""}}, "empty strings"},
		{"bad match name", QueryRequest{Collection: "r", Match: map[string]string{"value.secret": "x"}}, "invalid attribute name"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			checkErr(t, tt.req.Validate(), tt.wantErr)
		})
	}
}

func TestEffectiveLimit(t *testing.T) {
	if got := (QueryRequest{}).EffectiveLimit(); got != DefaultQueryLimit {
		t.Errorf("EffectiveLimit() = %d, want default %d", got, DefaultQueryLimit)
	}
	if got := (QueryRequest{Limit: 7}).EffectiveLimit(); got != 7 {
		t.Errorf("EffectiveLimit() = %d, want 7", got)
	}
}

func checkErr(t *testing.T, err error, want string) {
	t.Helper()
	if want == "" {
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		return
	}
	if err == nil || !strings.Contains(err.Error(), want) {
		t.Fatalf("error = %v, want it to contain %q", err, want)
	}
}
