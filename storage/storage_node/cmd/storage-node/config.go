package main

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"time"
)

// config is read only from environment variables (12-factor style), so the same
// image runs unchanged under Docker Compose now and Kubernetes later.
type config struct {
	Port         string        // SERVER_PORT
	NodeID       string        // NODE_ID
	MongoURI     string        // MONGO_URI
	MongoDB      string        // MONGO_DB
	MongoTimeout time.Duration // MONGO_TIMEOUT: bound on each database operation

	ShardID           int           // SHARD_ID: which shard this node holds a copy of
	CoordinatorURL    string        // COORDINATOR_URL: where to register and heartbeat
	AdvertiseURL      string        // ADVERTISE_URL: how the coordinator reaches this node
	HeartbeatInterval time.Duration // HEARTBEAT_INTERVAL
}

func loadConfig() (config, error) {
	timeout, err := time.ParseDuration(getenv("MONGO_TIMEOUT", "2s"))
	if err != nil {
		return config{}, fmt.Errorf("MONGO_TIMEOUT: %w", err)
	}
	nodeID := os.Getenv("NODE_ID")
	if nodeID == "" {
		return config{}, errors.New("NODE_ID is required (unique per node, e.g. storage-0-1)")
	}
	shard, err := strconv.Atoi(os.Getenv("SHARD_ID"))
	if err != nil || shard < 0 {
		return config{}, fmt.Errorf("SHARD_ID must be a shard number >= 0, got %q", os.Getenv("SHARD_ID"))
	}
	coordURL, advertiseURL := os.Getenv("COORDINATOR_URL"), os.Getenv("ADVERTISE_URL")
	for name, v := range map[string]string{"COORDINATOR_URL": coordURL, "ADVERTISE_URL": advertiseURL} {
		if u, err := url.Parse(v); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return config{}, fmt.Errorf("%s %q is not an http(s) URL", name, v)
		}
	}
	interval, err := time.ParseDuration(getenv("HEARTBEAT_INTERVAL", "5s"))
	if err != nil || interval <= 0 {
		return config{}, fmt.Errorf("HEARTBEAT_INTERVAL: invalid duration %q", os.Getenv("HEARTBEAT_INTERVAL"))
	}
	return config{
		Port:   getenv("SERVER_PORT", "8080"),
		NodeID: nodeID,
		// localhost is the default because in Kubernetes MongoDB runs as a sidecar in
		// the same pod; Compose overrides it with the mongo service name.
		MongoURI:     getenv("MONGO_URI", "mongodb://localhost:27017"),
		MongoDB:      getenv("MONGO_DB", "storage"),
		MongoTimeout: timeout,

		ShardID:           shard,
		CoordinatorURL:    coordURL,
		AdvertiseURL:      advertiseURL,
		HeartbeatInterval: interval,
	}, nil
}

func getenv(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
