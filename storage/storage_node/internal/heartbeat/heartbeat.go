// Package heartbeat registers this storage node with the coordinator and then
// reports its liveness every few seconds (contracts: POST /internal/nodes/register,
// POST /internal/nodes/heartbeat).
//
// The coordinator owns all cluster decisions (roles, failover, recovery); this
// package only keeps it informed. A node the coordinator does not know, or has
// declared DOWN, gets 404 on its heartbeat and simply registers again, which puts it
// through recovery sync before it serves reads.
package heartbeat

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

// Client sends registrations and heartbeats. All fields are required.
type Client struct {
	CoordinatorURL string
	NodeID         string
	ShardID        int
	URL            string // how the coordinator reaches this node
	Interval       time.Duration
	// MaxUpdatedAt reports the newest write on this node; the coordinator uses it to
	// pick the most up-to-date node on failover and as the start of a resync.
	MaxUpdatedAt func(context.Context) (time.Time, error)
	HTTP         *http.Client
	Log          *slog.Logger
}

// Run blocks until ctx is cancelled.
func (c *Client) Run(ctx context.Context) {
	registered := false
	t := time.NewTicker(c.Interval)
	defer t.Stop()
	for {
		path := "/internal/nodes/heartbeat"
		if !registered {
			path = "/internal/nodes/register"
		}
		role, status, err := c.send(ctx, path)
		switch {
		case err != nil:
			// Keep trying: the coordinator may be starting or restarting.
			c.Log.Warn("coordinator unreachable", "path", path, "err", err)
		case status == http.StatusNotFound && registered:
			// The coordinator forgot us (it restarted, or declared us DOWN): register
			// again right away rather than staying invisible for another interval.
			c.Log.Warn("coordinator does not know this node; registering again")
			registered = false
			continue
		case status == http.StatusNotFound:
			// Registration itself was not found: COORDINATOR_URL most likely points at
			// the wrong service. Retry on the normal schedule; looping immediately here
			// would flood that service and burn a CPU.
			c.Log.Error("coordinator has no registration endpoint; check COORDINATOR_URL", "url", c.CoordinatorURL)
		case status >= 300:
			c.Log.Warn("coordinator rejected node", "path", path, "status", status)
		default:
			if !registered {
				c.Log.Info("registered with coordinator", "role", role)
			}
			registered = true
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (c *Client) send(ctx context.Context, path string) (role string, status int, err error) {
	maxUpdated, err := c.MaxUpdatedAt(ctx)
	if err != nil {
		return "", 0, fmt.Errorf("read local max updatedAt: %w", err)
	}
	body, err := json.Marshal(model.NodeInfo{NodeID: c.NodeID, ShardID: c.ShardID, URL: c.URL, MaxUpdatedAt: maxUpdated})
	if err != nil {
		return "", 0, err
	}
	rctx, cancel := context.WithTimeout(ctx, c.Interval)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodPost, strings.TrimRight(c.CoordinatorURL, "/")+path, bytes.NewReader(body))
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()
	var out struct {
		Role string `json:"role"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out) // best effort: the role is only logged
	return out.Role, resp.StatusCode, nil
}
