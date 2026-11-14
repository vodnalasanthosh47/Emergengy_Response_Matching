package api

import (
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics holds the Prometheus metrics exposed on /metrics.
//
// It uses its own registry instead of the global default so that tests can create
// as many instances as they like without "duplicate metrics collector" panics.
type Metrics struct {
	registry       *prometheus.Registry
	requests       *prometheus.CounterVec
	duration       *prometheus.HistogramVec
	conflicts      prometheus.Counter
	upstreamErrors prometheus.Counter
}

// NewMetrics creates the metrics with the given name prefix (e.g. "coordinator").
func NewMetrics(namespace string) *Metrics {
	m := &Metrics{
		registry: prometheus.NewRegistry(),
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: namespace,
			Name:      "http_requests_total",
			Help:      "HTTP requests by route (method + pattern) and status code.",
		}, []string{"route", "code"}),
		duration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Namespace: namespace,
			Name:      "http_request_duration_seconds",
			Help:      "HTTP request latency by route pattern.",
			Buckets:   prometheus.DefBuckets,
		}, []string{"route"}),
		conflicts: prometheus.NewCounter(prometheus.CounterOpts{
			Namespace: namespace,
			Name:      "version_conflicts_total",
			Help:      "Writes rejected with 409 because expectedVersion did not match.",
		}),
		upstreamErrors: prometheus.NewCounter(prometheus.CounterOpts{
			Namespace: namespace,
			Name:      "upstream_errors_total",
			Help:      "Calls to the storage tier that failed (unreachable, timed out or 5xx).",
		}),
	}
	m.registry.MustRegister(
		m.requests, m.duration, m.conflicts, m.upstreamErrors,
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
	)
	return m
}

// Handler serves the metrics in Prometheus text format.
func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

// statusRecorder remembers the status code a handler wrote.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// instrument wraps a handler with metrics and request logging.
//
// The route label is the *pattern* ("PUT /internal/data/{key}"), never the concrete
// URL: one time series per key would grow without bound and overwhelm Prometheus.
func instrument(route string, m *Metrics, log *slog.Logger, next http.Handler) http.Handler {
	quiet := route == "GET /healthz" || route == "GET /readyz" // probed every few seconds
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		elapsed := time.Since(start)

		m.requests.WithLabelValues(route, strconv.Itoa(rec.status)).Inc()
		m.duration.WithLabelValues(route).Observe(elapsed.Seconds())

		level := slog.LevelInfo
		if rec.status >= 500 {
			level = slog.LevelError
		} else if quiet {
			level = slog.LevelDebug
		}
		log.Log(r.Context(), level, "request",
			"method", r.Method, "path", r.URL.Path, "status", rec.status,
			"duration_ms", elapsed.Milliseconds())
	})
}
