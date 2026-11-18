package main

import (
	"strings"
	"testing"
	"time"
)

// setEnv sets every storage-node variable for one test; t.Setenv restores them after.
func setEnv(t *testing.T, env map[string]string) {
	t.Helper()
	for _, name := range []string{"SERVER_PORT", "NODE_ID", "MONGO_URI", "MONGO_DB", "MONGO_TIMEOUT",
		"SHARD_ID", "COORDINATOR_URL", "ADVERTISE_URL", "HEARTBEAT_INTERVAL"} {
		t.Setenv(name, env[name])
	}
}

var validEnv = map[string]string{
	"NODE_ID": "storage-0-a", "SHARD_ID": "0",
	"COORDINATOR_URL": "http://coordinator:8080", "ADVERTISE_URL": "http://storage-0-a:8080",
}

func with(changes map[string]string) map[string]string {
	env := map[string]string{}
	for k, v := range validEnv {
		env[k] = v
	}
	for k, v := range changes {
		env[k] = v
	}
	return env
}

func TestLoadConfigDefaults(t *testing.T) {
	setEnv(t, validEnv)
	cfg, err := loadConfig()
	if err != nil {
		t.Fatal(err)
	}
	want := config{Port: "8080", NodeID: "storage-0-a", MongoURI: "mongodb://localhost:27017", MongoDB: "storage",
		MongoTimeout: 2 * time.Second, ShardID: 0, CoordinatorURL: "http://coordinator:8080",
		AdvertiseURL: "http://storage-0-a:8080", HeartbeatInterval: 5 * time.Second}
	if cfg != want {
		t.Fatalf("cfg = %+v\nwant  %+v", cfg, want)
	}
}

func TestLoadConfigOverrides(t *testing.T) {
	setEnv(t, with(map[string]string{"SERVER_PORT": "9000", "MONGO_URI": "mongodb://mongo-1-b:27017",
		"MONGO_DB": "x", "MONGO_TIMEOUT": "500ms", "SHARD_ID": "2", "HEARTBEAT_INTERVAL": "1s"}))
	cfg, err := loadConfig()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != "9000" || cfg.MongoURI != "mongodb://mongo-1-b:27017" || cfg.MongoDB != "x" ||
		cfg.MongoTimeout != 500*time.Millisecond || cfg.ShardID != 2 || cfg.HeartbeatInterval != time.Second {
		t.Fatalf("cfg = %+v", cfg)
	}
}

func TestLoadConfigRejectsBadValues(t *testing.T) {
	tests := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"missing NODE_ID", with(map[string]string{"NODE_ID": ""}), "NODE_ID"},
		{"missing SHARD_ID", with(map[string]string{"SHARD_ID": ""}), "SHARD_ID"},
		{"negative SHARD_ID", with(map[string]string{"SHARD_ID": "-1"}), "SHARD_ID"},
		{"non-numeric SHARD_ID", with(map[string]string{"SHARD_ID": "one"}), "SHARD_ID"},
		{"missing COORDINATOR_URL", with(map[string]string{"COORDINATOR_URL": ""}), "COORDINATOR_URL"},
		{"COORDINATOR_URL without scheme", with(map[string]string{"COORDINATOR_URL": "coordinator:8080"}), "COORDINATOR_URL"},
		{"ADVERTISE_URL not http", with(map[string]string{"ADVERTISE_URL": "ftp://x"}), "ADVERTISE_URL"},
		{"bad MONGO_TIMEOUT", with(map[string]string{"MONGO_TIMEOUT": "soon"}), "MONGO_TIMEOUT"},
		{"zero HEARTBEAT_INTERVAL", with(map[string]string{"HEARTBEAT_INTERVAL": "0s"}), "HEARTBEAT_INTERVAL"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			setEnv(t, tt.env)
			if _, err := loadConfig(); err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Fatalf("err = %v, want one mentioning %s", err, tt.wantErr)
			}
		})
	}
}
