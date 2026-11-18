// Package replication brings a recovering (or new) storage node up to date with its
// shard before it may serve reads or be promoted.
//
// While a node syncs it is already a replication target, so it receives every new
// write; the sync only has to fill in what it missed. Two passes, both against the
// shard's primary, whose copy always wins:
//
//  1. Copy: page through every record (tombstones included) the primary changed
//     since the node's newest write minus an overlap, and apply each as a replica
//     write. Replica writes keep the newer version, so replaying an old copy can
//     never undo a write that replication delivered meanwhile.
//  2. Repair: page through the records the node itself changed in the same window.
//     Any whose version differs from what the primary sent was written on the node
//     but never acknowledged (e.g. it accepted a write as primary, then crashed
//     before replicating it). Replace it with the primary's copy, or remove it if
//     the primary has none. Repairs are conditional on the node's version, so they
//     too never undo a newer replicated write.
//
// Then the registry marks the node ACTIVE.
package replication

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/store"
)

// errSuperseded means the node re-registered or went down during the sync: a newer
// task (or none) is responsible for it now.
var errSuperseded = errors.New("sync superseded")

// Syncer consumes the registry's sync tasks.
type Syncer struct {
	Reg      *registry.Registry
	Pool     *store.Pool
	Overlap  time.Duration // re-copied window before the node's newest write (clock skew)
	PageSize int
	Retry    time.Duration // wait before retrying a failed sync
	Log      *slog.Logger
}

// Result summarises one completed sync.
type Result struct {
	Copied, Repaired, Purged int
}

// Run handles sync tasks until ctx is cancelled, one goroutine per task.
func (s *Syncer) Run(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case t := <-s.Reg.SyncTasks():
			go s.handle(ctx, t)
		}
	}
}

// handle retries a sync until it succeeds or the task is superseded. Typical
// failures are transient: the primary is being replaced, or the node is restarting.
func (s *Syncer) handle(ctx context.Context, t registry.SyncTask) {
	for {
		start := time.Now()
		res, err := s.SyncOnce(ctx, t)
		if err == nil {
			s.Log.Info("recovery sync complete", "node", t.NodeID, "copied", res.Copied,
				"repaired", res.Repaired, "purged", res.Purged, "took", time.Since(start).Round(time.Millisecond))
			return
		}
		if errors.Is(err, errSuperseded) || !s.Reg.StillSyncing(t) {
			return
		}
		s.Log.Warn("recovery sync failed; retrying", "node", t.NodeID, "err", err, "in", s.Retry)
		select {
		case <-ctx.Done():
			return
		case <-time.After(s.Retry):
		}
	}
}

// SyncOnce runs both passes once and, on success, marks the node ACTIVE.
func (s *Syncer) SyncOnce(ctx context.Context, t registry.SyncTask) (Result, error) {
	var res Result
	n, ok := s.Reg.Get(t.NodeID)
	if !ok || n.Epoch != t.Epoch || n.State != registry.StateSyncing {
		return res, errSuperseded
	}
	p, ok := s.Reg.Primary(n.Shard)
	if !ok {
		return res, fmt.Errorf("shard %d has no primary to sync from", n.Shard)
	}
	since := time.Time{} // an empty node copies everything
	if !n.MaxUpdatedAt.IsZero() {
		since = n.MaxUpdatedAt.Add(-s.Overlap)
	}
	src, dst := s.Pool.For(p.URL), s.Pool.For(n.URL)
	s.Log.Info("recovery sync started", "node", n.ID, "shard", n.Shard, "from", p.ID, "since", since)

	// Pass 1: copy the primary's changes, remembering the version of each key sent.
	sent := map[string]int64{}
	err := s.pages(ctx, t, src, since, func(rec model.Record) error {
		sent[rec.Key] = rec.Version
		applied, err := dst.ApplyReplica(ctx, rec)
		if applied {
			res.Copied++
		}
		return err
	})
	if err != nil {
		return res, fmt.Errorf("copy from %s: %w", p.ID, err)
	}

	// Pass 2: repair the node's own changes in the window that the primary does not
	// have in the same version.
	err = s.pages(ctx, t, dst, since, func(local model.Record) error {
		if v, ok := sent[local.Key]; ok && v == local.Version {
			return nil // identical to the primary's copy
		}
		authoritative, err := src.GetAny(ctx, local.Key)
		switch {
		case errors.Is(err, store.ErrNotFound):
			if ok, err := dst.Purge(ctx, local.Key, local.Version); err != nil {
				return err
			} else if ok {
				res.Purged++
			}
		case err != nil:
			return err
		case authoritative.Version == local.Version && authoritative.UpdatedAt.Equal(local.UpdatedAt):
			// Same write (it was replicated after pass 1 listed the primary).
		default:
			if ok, err := dst.Repair(ctx, authoritative, local.Version); err != nil {
				return err
			} else if ok {
				res.Repaired++
			}
		}
		return nil
	})
	if err != nil {
		return res, fmt.Errorf("repair %s: %w", n.ID, err)
	}

	if !s.Reg.SetActive(t) {
		return res, errSuperseded
	}
	return res, nil
}

// pages calls fn for every record a node changed after since, page by page,
// stopping early if the task is superseded.
func (s *Syncer) pages(ctx context.Context, t registry.SyncTask, node *store.Forwarder, since time.Time, fn func(model.Record) error) error {
	after := ""
	for {
		if !s.Reg.StillSyncing(t) {
			return errSuperseded
		}
		page, err := node.Sync(ctx, since, after, s.PageSize)
		if err != nil {
			return err
		}
		for _, rec := range page.Records {
			if err := fn(rec); err != nil {
				return err
			}
		}
		if !page.More {
			return nil
		}
		since, after = page.NextSince, page.NextKey
	}
}
