# routing — CP2

Placeholder. Consistent-hash ring with 3 storage nodes: maps a key's routing key (request uuid or resource geo cell) to its owning shard. Will provide a new `store.RecordStore` implementation that replaces the single-node `Forwarder`.

Not implemented in CP1 (see PROJECT_SPEC §7.2). This folder exists so the layout matches the
spec and the seam for later work is obvious.
