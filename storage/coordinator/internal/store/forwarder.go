package store

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
)

// Forwarder is the HTTP client for one storage node (contracts/openapi/
// storage-node-api.yaml). ShardedStore picks the node; the Forwarder only talks to it.
type Forwarder struct {
	baseURL string
	client  *http.Client
	timeout time.Duration
}

// NewForwarder creates a Forwarder. timeout bounds each call to the storage node.
func NewForwarder(baseURL string, timeout time.Duration) *Forwarder {
	return newForwarder(baseURL, timeout, newTransport())
}

func newForwarder(baseURL string, timeout time.Duration, transport *http.Transport) *Forwarder {
	return &Forwarder{
		baseURL: strings.TrimRight(baseURL, "/"),
		// No client-level Timeout: each call gets a context deadline instead, which also
		// cancels the request on the node side when our own caller goes away.
		client:  &http.Client{Transport: transport},
		timeout: timeout,
	}
}

func newTransport() *http.Transport {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	// Go's default keeps only 2 idle connections per host. Allow more to be reused
	// instead of opening a new TCP connection per call.
	transport.MaxIdleConnsPerHost = 64
	return transport
}

func (f *Forwarder) Get(ctx context.Context, key string) (model.Record, error) {
	var rec model.Record
	_, err := f.do(ctx, http.MethodGet, dataPath(key), nil, &rec)
	return rec, err
}

func (f *Forwarder) Put(ctx context.Context, key string, req model.PutRequest) (model.Record, bool, error) {
	var rec model.Record
	status, err := f.do(ctx, http.MethodPut, dataPath(key), req, &rec)
	return rec, status == http.StatusCreated, err
}

// Delete returns the tombstone the node wrote, so that it can be replicated.
func (f *Forwarder) Delete(ctx context.Context, key string, expectedVersion *int64) (model.Record, error) {
	path := dataPath(key)
	if expectedVersion != nil {
		path += "?expectedVersion=" + strconv.FormatInt(*expectedVersion, 10)
	}
	var tomb model.Record
	_, err := f.do(ctx, http.MethodDelete, path, nil, &tomb)
	return tomb, err
}

// ApplyReplica sends a copy of a primary's write. applied=false means the node
// already holds a newer version, which is not an error.
func (f *Forwarder) ApplyReplica(ctx context.Context, rec model.Record) (bool, error) {
	var out struct {
		Applied bool `json:"applied"`
	}
	_, err := f.do(ctx, http.MethodPut, "/internal/replica/"+url.PathEscape(rec.Key), rec, &out)
	return out.Applied, err
}

// GetAny reads a record including tombstones.
func (f *Forwarder) GetAny(ctx context.Context, key string) (model.Record, error) {
	var rec model.Record
	_, err := f.do(ctx, http.MethodGet, "/internal/replica/"+url.PathEscape(key), nil, &rec)
	return rec, err
}

// Repair overwrites key with rec if the node still holds localVersion.
func (f *Forwarder) Repair(ctx context.Context, rec model.Record, localVersion int64) (bool, error) {
	var out struct {
		Applied bool `json:"applied"`
	}
	path := "/internal/repair/" + url.PathEscape(rec.Key) + "?expectedLocalVersion=" + strconv.FormatInt(localVersion, 10)
	_, err := f.do(ctx, http.MethodPut, path, rec, &out)
	return out.Applied, err
}

// Purge removes key if the node still holds localVersion.
func (f *Forwarder) Purge(ctx context.Context, key string, localVersion int64) (bool, error) {
	var out struct {
		Applied bool `json:"applied"`
	}
	path := "/internal/repair/" + url.PathEscape(key) + "?expectedLocalVersion=" + strconv.FormatInt(localVersion, 10)
	_, err := f.do(ctx, http.MethodDelete, path, nil, &out)
	return out.Applied, err
}

// Sync reads one page of the node's records changed after (since, afterKey).
func (f *Forwarder) Sync(ctx context.Context, since time.Time, afterKey string, limit int) (model.SyncPage, error) {
	q := url.Values{}
	if !since.IsZero() {
		q.Set("since", since.UTC().Format(time.RFC3339Nano))
	}
	if afterKey != "" {
		q.Set("afterKey", afterKey)
	}
	q.Set("limit", strconv.Itoa(limit))
	var page model.SyncPage
	_, err := f.do(ctx, http.MethodGet, "/internal/sync?"+q.Encode(), nil, &page)
	return page, err
}

// Stats reads the node's record counts.
func (f *Forwarder) Stats(ctx context.Context) (model.Stats, error) {
	var st model.Stats
	_, err := f.do(ctx, http.MethodGet, "/internal/stats", nil, &st)
	return st, err
}

func (f *Forwarder) Query(ctx context.Context, q model.QueryRequest) ([]model.Record, error) {
	var resp model.QueryResponse
	if _, err := f.do(ctx, http.MethodPost, "/internal/query", q, &resp); err != nil {
		return nil, err
	}
	if resp.Records == nil {
		resp.Records = []model.Record{}
	}
	return resp.Records, nil
}

func (f *Forwarder) Ready(ctx context.Context) error {
	_, err := f.do(ctx, http.MethodGet, "/readyz", nil, nil)
	return err
}

func dataPath(key string) string {
	return "/internal/data/" + url.PathEscape(key)
}

// do sends one request to the storage node. A 2xx response is decoded into out (if
// non-nil); any other outcome becomes one of the package's sentinel errors, so the
// node's 404/409/412 reach our caller unchanged.
func (f *Forwarder) do(ctx context.Context, method, path string, body, out any) (int, error) {
	ctx, cancel := context.WithTimeout(ctx, f.timeout)
	defer cancel()

	var reqBody io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, fmt.Errorf("encode request: %w", err)
		}
		reqBody = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, f.baseURL+path, reqBody)
	if err != nil {
		return 0, fmt.Errorf("build request: %w", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := f.client.Do(req)
	if err != nil {
		// Connection refused, DNS failure, timeout...: the node is unreachable.
		return 0, fmt.Errorf("%w: %s %s: %v", ErrUnavailable, method, path, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		if out != nil && resp.StatusCode != http.StatusNoContent {
			if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
				return resp.StatusCode, fmt.Errorf("%w: invalid response from storage node: %v", ErrUnavailable, err)
			}
		}
		return resp.StatusCode, nil
	}
	return resp.StatusCode, errorFromResponse(resp)
}

// errorFromResponse converts a non-2xx storage-node response into a sentinel error.
func errorFromResponse(resp *http.Response) error {
	var body struct {
		Message string `json:"message"`
	}
	// Best effort: the error body only improves the message.
	_ = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&body)

	switch resp.StatusCode {
	case http.StatusNotFound:
		return ErrNotFound
	case http.StatusConflict:
		return ErrVersionConflict
	case http.StatusPreconditionFailed:
		return ErrPreconditionFailed
	case http.StatusBadRequest:
		return fmt.Errorf("%w: %s", ErrInvalidRequest, body.Message)
	default:
		// 5xx (or anything unexpected): the storage tier failed. The contract reports
		// all of these to services as 503 so they have a single "retry later" signal.
		return fmt.Errorf("%w: storage node returned %d: %s", ErrUnavailable, resp.StatusCode, body.Message)
	}
}
