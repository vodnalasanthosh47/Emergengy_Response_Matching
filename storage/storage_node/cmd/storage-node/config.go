package main

import (
	"fmt"
	"os"
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
}

func loadConfig() (config, error) {
	timeout, err := time.ParseDuration(getenv("MONGO_TIMEOUT", "2s"))
	if err != nil {
		return config{}, fmt.Errorf("MONGO_TIMEOUT: %w", err)
	}
	return config{
		Port:   getenv("SERVER_PORT", "8080"),
		NodeID: getenv("NODE_ID", "node-0"),
		// localhost is the default because in Kubernetes MongoDB runs as a sidecar in
		// the same pod; Compose overrides it with the mongo service name.
		MongoURI:     getenv("MONGO_URI", "mongodb://localhost:27017"),
		MongoDB:      getenv("MONGO_DB", "storage"),
		MongoTimeout: timeout,
	}, nil
}

func getenv(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
