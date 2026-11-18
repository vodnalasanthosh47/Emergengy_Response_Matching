// Command coordinator is the single entry point to the storage tier. It holds no
// application data, only the soft-state registry of storage nodes. This file is
// wiring only: config, registry, sharded store, recovery syncer, HTTP server, shutdown.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/api"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/replication"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

func main() {
	// The runtime image (distroless) has no shell or curl, so the binary probes itself
	// for the Docker HEALTHCHECK: `coordinator -healthcheck`.
	healthcheck := flag.Bool("healthcheck", false, "probe /readyz of the local server and exit 0 if ready, 1 otherwise")
	flag.Parse()
	if *healthcheck {
		os.Exit(probe(getenv("SERVER_PORT", "8080")))
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("component", "coordinator")
	cfg, err := loadConfig()
	if err != nil {
		logger.Error("invalid configuration", "err", err)
		os.Exit(1)
	}
	if err := run(cfg, logger); err != nil {
		logger.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(cfg config, logger *slog.Logger) error {
	// ctx is cancelled on SIGTERM (what Docker and Kubernetes send on stop) or Ctrl+C.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	reg := registry.New(registry.Config{
		Shards:           cfg.Shards,
		HeartbeatTimeout: cfg.HeartbeatTimeout,
		ElectionWindow:   cfg.ElectionWindow,
		Log:              logger,
	})
	go reg.RunSweeper(ctx, cfg.SweepInterval)

	pool := store.NewPool(cfg.StorageTimeout)
	sharded := store.NewSharded(reg, pool, logger)
	syncer := &replication.Syncer{Reg: reg, Pool: pool, Overlap: cfg.SyncOverlap, PageSize: 500, Retry: 2 * time.Second, Log: logger}
	go syncer.Run(ctx)

	metrics := api.NewMetrics("coordinator")
	metrics.WatchCluster(reg.Snapshot, reg.Failovers)
	cluster := api.Cluster{Nodes: reg, Topology: func(ctx context.Context) any { return sharded.Topology(ctx) }}

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           api.NewHandler(sharded, cluster, logger, metrics, cfg.StorageTimeout),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	serveErr := make(chan error, 1)
	go func() {
		logger.Info("listening", "addr", srv.Addr, "shards", cfg.Shards, "heartbeat_timeout", cfg.HeartbeatTimeout)
		if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			serveErr <- err
		}
	}()

	select {
	case err := <-serveErr:
		return fmt.Errorf("http server: %w", err)
	case <-ctx.Done():
	}

	// Graceful shutdown: stop accepting connections, let in-flight requests finish.
	logger.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return srv.Shutdown(sctx)
}

func probe(port string) int {
	c := http.Client{Timeout: 2 * time.Second}
	resp, err := c.Get("http://127.0.0.1:" + port + "/readyz")
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
