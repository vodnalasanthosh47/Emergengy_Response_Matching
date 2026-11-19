package com.emergency.request.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestView;
import com.emergency.request.domain.ConcurrentUpdateException;
import com.emergency.request.domain.DependencyUnavailableException;
import com.emergency.request.domain.RequestEventPublisher;
import com.emergency.request.domain.RequestNotFoundException;
import com.emergency.request.domain.RequestRepository;
import com.emergency.request.domain.StoredRequest;

/** Test doubles for the domain's two ports, honouring expectedVersion like the real storage. */
public final class InMemoryRequests {

    private InMemoryRequests() {
    }

    public static class Repository implements RequestRepository {
        public final Map<String, StoredRequest> records = new ConcurrentHashMap<>();
        /** Runs just before an update is applied, to simulate a concurrent writer. */
        public Consumer<String> beforeUpdate = id -> { };
        public boolean down;
        public int updates;
        public int deletes;

        @Override
        public synchronized StoredRequest create(RequestDocument request) {
            checkUp();
            StoredRequest stored = new StoredRequest(request, 1);
            if (records.putIfAbsent(request.requestId(), stored) != null) {
                throw new IllegalStateException("exists");
            }
            return stored;
        }

        @Override
        public Optional<StoredRequest> find(String requestId) {
            checkUp();
            return Optional.ofNullable(records.get(requestId));
        }

        @Override
        public synchronized StoredRequest update(RequestDocument request, long expectedVersion) {
            checkUp();
            beforeUpdate.accept(request.requestId());
            StoredRequest current = records.get(request.requestId());
            if (current == null) {
                throw new RequestNotFoundException("gone");
            }
            if (current.version() != expectedVersion) {
                throw new ConcurrentUpdateException("changed");
            }
            updates++;
            StoredRequest next = new StoredRequest(request, expectedVersion + 1);
            records.put(request.requestId(), next);
            return next;
        }

        @Override
        public synchronized void delete(String requestId, long expectedVersion) {
            checkUp();
            deletes++;
            StoredRequest current = records.get(requestId);
            if (current != null && current.version() == expectedVersion) {
                records.remove(requestId);
            }
        }

        /** Another writer changes the request's status behind our back. */
        public synchronized void concurrentWrite(String requestId, RequestDocument newDocument) {
            StoredRequest current = records.get(requestId);
            records.put(requestId, new StoredRequest(newDocument, current.version() + 1));
        }

        private void checkUp() {
            if (down) {
                throw new DependencyUnavailableException("storage down");
            }
        }
    }

    public static class Publisher implements RequestEventPublisher {
        public final List<RequestView> published = new ArrayList<>();
        public boolean down;

        @Override
        public void publishCreated(RequestView request) {
            if (down) {
                throw new DependencyUnavailableException("broker down");
            }
            published.add(request);
        }
    }
}
