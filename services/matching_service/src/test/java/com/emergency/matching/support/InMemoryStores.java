package com.emergency.matching.support;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.emergency.common.model.Category;
import com.emergency.common.request.RequestDocument;
import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceStatus;
import com.emergency.matching.domain.ConcurrentUpdateException;
import com.emergency.matching.domain.DependencyUnavailableException;
import com.emergency.matching.domain.RequestStore;
import com.emergency.matching.domain.ResourceStore;
import com.emergency.matching.domain.Versioned;

/**
 * Test doubles for the two stores. Writes are compare-and-set on the version, exactly like
 * the real storage, and synchronized, so concurrency tests with real threads are meaningful.
 */
public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static class Requests implements RequestStore {
        public final Map<String, Versioned<RequestDocument>> records = new ConcurrentHashMap<>();
        public final AtomicInteger updates = new AtomicInteger();
        /** Runs just before an update is checked, e.g. to simulate a concurrent writer. */
        public volatile Consumer<String> beforeUpdate = id -> { };
        /** Runs after an update was applied, before its answer is returned (or lost). */
        public volatile Consumer<String> afterUpdate = id -> { };
        /** Every update fails with "storage unavailable" without being applied. */
        public volatile boolean updatesFail;
        /** The next N updates fail with "storage unavailable" without being applied. */
        public final AtomicInteger unappliedFailures = new AtomicInteger();
        /**
         * The next N updates are applied, but the caller gets "storage unavailable" instead of
         * the answer (e.g. the primary was replaced mid-write, or the answer timed out).
         */
        public final AtomicInteger lostAnswers = new AtomicInteger();
        /** Reads fail with "storage unavailable". */
        public volatile boolean findsFail;

        public void put(RequestDocument doc) {
            records.put(doc.requestId(), new Versioned<>(doc, 1));
        }

        public RequestDocument get(String id) {
            return records.get(id).document();
        }

        public long version(String id) {
            return records.get(id).version();
        }

        @Override
        public Optional<Versioned<RequestDocument>> find(String requestId) {
            if (findsFail) {
                throw new DependencyUnavailableException("storage down");
            }
            return Optional.ofNullable(records.get(requestId));
        }

        @Override
        public Versioned<RequestDocument> update(RequestDocument request, long expectedVersion) {
            beforeUpdate.accept(request.requestId());
            if (updatesFail || unappliedFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new DependencyUnavailableException("storage down");
            }
            Versioned<RequestDocument> next;
            synchronized (this) {
                Versioned<RequestDocument> current = records.get(request.requestId());
                if (current == null || current.version() != expectedVersion) {
                    throw new ConcurrentUpdateException("request changed");
                }
                updates.incrementAndGet();
                next = new Versioned<>(request, expectedVersion + 1);
                records.put(request.requestId(), next);
            }
            afterUpdate.accept(request.requestId());
            if (lostAnswers.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new DependencyUnavailableException("primary replaced during the write; outcome unknown");
            }
            return next;
        }

        /** Another writer (a client, another matcher) changes the request behind our back. */
        public synchronized void concurrentWrite(RequestDocument doc) {
            Versioned<RequestDocument> current = records.get(doc.requestId());
            records.put(doc.requestId(), new Versioned<>(doc, current.version() + 1));
        }
    }

    public static class Resources implements ResourceStore {
        public final Map<String, Versioned<ResourceDocument>> records = new ConcurrentHashMap<>();
        public final AtomicInteger updates = new AtomicInteger();
        public volatile Consumer<String> beforeUpdate = id -> { };

        public void put(ResourceDocument doc) {
            records.put(doc.resourceId(), new Versioned<>(doc, 1));
        }

        public ResourceDocument get(String id) {
            return records.get(id).document();
        }

        @Override
        public List<Versioned<ResourceDocument>> findAvailable(Category type, List<String> cells, int limit) {
            return records.values().stream()
                    .filter(r -> r.document().type() == type && r.document().status() == ResourceStatus.AVAILABLE
                            && cells.contains(r.document().geoCell()))
                    .sorted(Comparator.comparing(r -> r.document().resourceId()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<Versioned<ResourceDocument>> find(String resourceId) {
            return Optional.ofNullable(records.get(resourceId));
        }

        @Override
        public Versioned<ResourceDocument> update(ResourceDocument resource, long expectedVersion) {
            beforeUpdate.accept(resource.resourceId());
            synchronized (this) {
                Versioned<ResourceDocument> current = records.get(resource.resourceId());
                if (current == null || current.version() != expectedVersion) {
                    throw new ConcurrentUpdateException("resource changed");
                }
                updates.incrementAndGet();
                Versioned<ResourceDocument> next = new Versioned<>(resource, expectedVersion + 1);
                records.put(resource.resourceId(), next);
                return next;
            }
        }

        public synchronized void concurrentWrite(ResourceDocument doc) {
            Versioned<ResourceDocument> current = records.get(doc.resourceId());
            records.put(doc.resourceId(), new Versioned<>(doc, current.version() + 1));
        }
    }
}
