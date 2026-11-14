package main

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"time"
)

// config is read only from environment variables, so the same image runs unchanged
// under Docker Compose now and Kubernetes later.
type config struct {
	Port           string        // SERVER_PORT
	StorageNodeURL string        // STORAGE_NODE_URL (CP1 only; replaced by node registration in CP2)
	StorageTimeout time.Duration // STORAGE_TIMEOUT: bound on each call to the storage node
}

func loadConfig() (config, error) {
	nodeURL := os.Getenv("STORAGE_NODE_URL")
	if nodeURL == "" {
		return config{}, errors.New("STORAGE_NODE_URL is required (e.g. http://storage-node:8080)")
	}
	if u, err := url.Parse(nodeURL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return config{}, fmt.Errorf("STORAGE_NODE_URL %q is not an http(s) URL", nodeURL)
	}

	// Default 3s: slightly longer than the storage node's own 2s MongoDB timeout, so
	// when Mongo is slow the node answers 503 itself rather than us cutting it off.
	timeout, err := time.ParseDuration(getenv("STORAGE_TIMEOUT", "3s"))
	if err != nil {
		return config{}, fmt.Errorf("STORAGE_TIMEOUT: %w", err)
	}
	return config{
		Port:           getenv("SERVER_PORT", "8080"),
		StorageNodeURL: nodeURL,
		StorageTimeout: timeout,
	}, nil
}

func getenv(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
