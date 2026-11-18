package main

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// config is read only from environment variables, so the same image runs unchanged
// under Docker Compose and Kubernetes. Storage nodes are not configured here: they
// register themselves (POST /internal/nodes/register).
type config struct {
	Port             string        // SERVER_PORT
	Shards           int           // SHARD_COUNT: number of shards keys are spread over
	StorageTimeout   time.Duration // STORAGE_TIMEOUT: bound on each call to a storage node
	HeartbeatTimeout time.Duration // HEARTBEAT_TIMEOUT: silence after which a node is DOWN
	SweepInterval    time.Duration // SWEEP_INTERVAL: how often failure detection runs
	ElectionWindow   time.Duration // ELECTION_WINDOW: wait for nodes before electing a primary
	SyncOverlap      time.Duration // SYNC_OVERLAP: re-copied window before a node's newest write
}

func loadConfig() (config, error) {
	shards, err := strconv.Atoi(getenv("SHARD_COUNT", "3"))
	if err != nil || shards < 1 {
		return config{}, fmt.Errorf("SHARD_COUNT must be a positive integer, got %q", os.Getenv("SHARD_COUNT"))
	}
	cfg := config{Port: getenv("SERVER_PORT", "8080"), Shards: shards}

	for _, d := range []struct {
		env, fallback string
		dst           *time.Duration
	}{
		// 3s: slightly longer than the storage node's own 2s MongoDB timeout, so when
		// Mongo is slow the node answers 503 itself rather than us cutting it off.
		{"STORAGE_TIMEOUT", "3s", &cfg.StorageTimeout},
		// Nodes heartbeat every 5s: three missed heartbeats mark a node DOWN.
		{"HEARTBEAT_TIMEOUT", "15s", &cfg.HeartbeatTimeout},
		{"SWEEP_INTERVAL", "1s", &cfg.SweepInterval},
		// Two heartbeat intervals: enough for every live node of a shard to register
		// after a coordinator restart, so the most up-to-date one is elected.
		{"ELECTION_WINDOW", "10s", &cfg.ElectionWindow},
		// Absorbs clock skew between the nodes that stamp updatedAt.
		{"SYNC_OVERLAP", "30s", &cfg.SyncOverlap},
	} {
		v, err := time.ParseDuration(getenv(d.env, d.fallback))
		if err != nil || v <= 0 {
			return config{}, fmt.Errorf("%s must be a positive duration, got %q", d.env, os.Getenv(d.env))
		}
		*d.dst = v
	}
	return cfg, nil
}

func getenv(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
