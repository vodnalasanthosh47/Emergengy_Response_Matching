package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

// maxBodyBytes caps request bodies. Domain documents are small; a cap stops a buggy or
// malicious client from making us buffer arbitrarily large payloads.
const maxBodyBytes = 1 << 20 // 1 MiB

// Error codes from the contract's Error schema.
const (
	codeInvalidRequest     = "INVALID_REQUEST"
	codeNotFound           = "NOT_FOUND"
	codeVersionConflict    = "VERSION_CONFLICT"
	codePreconditionFailed = "PRECONDITION_FAILED"
	codeStorageUnavailable = "STORAGE_UNAVAILABLE"
	codeInternalError      = "INTERNAL_ERROR"
)

type errorBody struct {
	Error     string `json:"error"`
	Message   string `json:"message"`
	Timestamp string `json:"timestamp"`
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorBody{
		Error:     code,
		Message:   message,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
	})
}

// decodeJSON reads exactly one JSON object from the body into dst.
//
// Unknown fields are rejected on purpose: a misspelling such as "expectedVerison"
// would otherwise be silently ignored and turn a conditional claim into an
// unconditional overwrite — exactly the double-booking bug we must never have.
// (encoding/json matches names case-insensitively, so "expectedversion" binds to
// expectedVersion and stays conditional; only real misspellings need catching.)
func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return fmt.Errorf("request body larger than %d bytes", maxBodyBytes)
		}
		return fmt.Errorf("invalid JSON body: %v", err)
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("request body must contain a single JSON object")
	}
	return nil
}
