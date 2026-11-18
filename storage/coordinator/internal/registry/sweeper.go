package registry

import (
	"context"
	"time"
)

// RunSweeper calls Sweep every interval until ctx is cancelled.
func (r *Registry) RunSweeper(ctx context.Context, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			r.Sweep()
		}
	}
}
