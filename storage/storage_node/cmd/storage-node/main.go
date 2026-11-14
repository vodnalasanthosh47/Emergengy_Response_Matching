// Command storage-node serves one shard of records from a standalone local MongoDB.
// This file is wiring only: config, Mongo client, store, HTTP server, shutdown.
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

	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/api"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/store"
)

func main() {
	// The runtime image (distroless) has no shell or curl, so the binary probes itself
	// for the Docker HEALTHCHECK: `storage-node -healthcheck`.
	healthcheck := flag.Bool("healthcheck", false, "probe /readyz of the local server and exit 0 if ready, 1 otherwise")
	flag.Parse()

	cfg, err := loadConfig()
	if err != nil {
		fmt.Fprintln(os.Stderr, "config:", err)
		os.Exit(1)
	}
	if *healthcheck {
		os.Exit(probe(cfg.Port))
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("component", "storage-node", "node_id", cfg.NodeID)
	if err := run(cfg, logger); err != nil {
		logger.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(cfg config, logger *slog.Logger) error {
	// ctx is cancelled on SIGTERM (what Docker and Kubernetes send on stop) or Ctrl+C.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	client, err := mongo.Connect(options.Client().
		ApplyURI(cfg.MongoURI).
		SetServerSelectionTimeout(cfg.MongoTimeout). // fail fast (→ 503) when Mongo is down
		SetConnectTimeout(cfg.MongoTimeout))
	if err != nil {
		return fmt.Errorf("mongo client: %w", err)
	}
	defer func() {
		dctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = client.Disconnect(dctx)
	}()

	st := store.New(client.Database(cfg.MongoDB))
	if err := ensureIndexes(ctx, st, logger); err != nil {
		return err
	}

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: api.NewHandler(st, logger, api.NewMetrics("storage_node"), cfg.MongoTimeout),
		// Timeouts protect the server from slow or stuck clients holding connections open.
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	serveErr := make(chan error, 1)
	go func() {
		logger.Info("listening", "addr", srv.Addr, "mongo_db", cfg.MongoDB)
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

// ensureIndexes retries for a while because MongoDB may still be starting — in
// Kubernetes the Mongo sidecar and this container start at the same time.
func ensureIndexes(ctx context.Context, st *store.MongoStore, logger *slog.Logger) error {
	const attempts = 30
	for i := 1; ; i++ {
		ictx, cancel := context.WithTimeout(ctx, 5*time.Second)
		err := st.EnsureIndexes(ictx)
		cancel()
		if err == nil {
			logger.Info("indexes ready")
			return nil
		}
		if i == attempts {
			return fmt.Errorf("create indexes after %d attempts: %w", attempts, err)
		}
		logger.Warn("mongodb not ready, retrying", "attempt", i, "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
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
