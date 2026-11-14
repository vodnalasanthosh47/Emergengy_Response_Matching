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

// Forwarder is the CP1 RecordStore: it relays every operation to one storage node
// whose base URL comes from configuration (STORAGE_NODE_URL).
type Forwarder struct {
	baseURL string
	client  *http.Client
	timeout time.Duration
}

var _ RecordStore = (*Forwarder)(nil) // compile-time check that Forwarder implements RecordStore

// NewForwarder creates a Forwarder. timeout bounds each call to the storage node.
func NewForwarder(baseURL string, timeout time.Duration) *Forwarder {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	// Go's default keeps only 2 idle connections per host. All our traffic goes to one
	// host, so allow more to be reused instead of opening a new TCP connection per call.
	transport.MaxIdleConnsPerHost = 64
	return &Forwarder{
		baseURL: strings.TrimRight(baseURL, "/"),
		// No client-level Timeout: each call gets a context deadline instead, which also
		// cancels the request on the node side when our own caller goes away.
		client:  &http.Client{Transport: transport},
		timeout: timeout,
	}
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

func (f *Forwarder) Delete(ctx context.Context, key string, expectedVersion *int64) error {
	path := dataPath(key)
	if expectedVersion != nil {
		path += "?expectedVersion=" + strconv.FormatInt(*expectedVersion, 10)
	}
	_, err := f.do(ctx, http.MethodDelete, path, nil, nil)
	return err
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
