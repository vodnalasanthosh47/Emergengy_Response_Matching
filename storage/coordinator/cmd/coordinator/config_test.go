package main

import (
	"strings"
	"testing"
	"time"
)

var coordinatorVars = []string{"SERVER_PORT", "SHARD_COUNT", "STORAGE_TIMEOUT", "HEARTBEAT_TIMEOUT",
	"SWEEP_INTERVAL", "ELECTION_WINDOW", "SYNC_OVERLAP"}

func setEnv(t *testing.T, env map[string]string) {
	t.Helper()
	for _, name := range coordinatorVars {
		t.Setenv(name, env[name])
	}
}

func TestLoadConfigDefaults(t *testing.T) {
	setEnv(t, nil)
	cfg, err := loadConfig()
	if err != nil {
		t.Fatal(err)
	}
	want := config{Port: "8080", Shards: 3, StorageTimeout: 3 * time.Second, HeartbeatTimeout: 15 * time.Second,
		SweepInterval: time.Second, ElectionWindow: 10 * time.Second, SyncOverlap: 30 * time.Second}
	if cfg != want {
		t.Fatalf("cfg = %+v\nwant  %+v", cfg, want)
	}
	// The failure detector must allow at least two missed 5 s heartbeats.
	if cfg.HeartbeatTimeout < 2*5*time.Second {
		t.Errorf("HEARTBEAT_TIMEOUT default %v is too short for 5s heartbeats", cfg.HeartbeatTimeout)
	}
}

func TestLoadConfigOverrides(t *testing.T) {
	setEnv(t, map[string]string{"SERVER_PORT": "9090", "SHARD_COUNT": "5", "STORAGE_TIMEOUT": "1s",
		"HEARTBEAT_TIMEOUT": "6s", "SWEEP_INTERVAL": "200ms", "ELECTION_WINDOW": "2s", "SYNC_OVERLAP": "1m"})
	cfg, err := loadConfig()
	if err != nil {
		t.Fatal(err)
	}
	want := config{Port: "9090", Shards: 5, StorageTimeout: time.Second, HeartbeatTimeout: 6 * time.Second,
		SweepInterval: 200 * time.Millisecond, ElectionWindow: 2 * time.Second, SyncOverlap: time.Minute}
	if cfg != want {
		t.Fatalf("cfg = %+v\nwant  %+v", cfg, want)
	}
}

func TestLoadConfigRejectsBadValues(t *testing.T) {
	for _, tt := range []struct{ name, value string }{
		{"SHARD_COUNT", "0"}, {"SHARD_COUNT", "-3"}, {"SHARD_COUNT", "three"},
		{"STORAGE_TIMEOUT", "3"}, {"STORAGE_TIMEOUT", "0s"}, {"HEARTBEAT_TIMEOUT", "-1s"},
		{"SWEEP_INTERVAL", "often"}, {"ELECTION_WINDOW", "0"}, {"SYNC_OVERLAP", "x"},
	} {
		t.Run(tt.name+"="+tt.value, func(t *testing.T) {
			setEnv(t, map[string]string{tt.name: tt.value})
			if _, err := loadConfig(); err == nil || !strings.Contains(err.Error(), tt.name) {
				t.Fatalf("err = %v, want one mentioning %s", err, tt.name)
			}
		})
	}
}
