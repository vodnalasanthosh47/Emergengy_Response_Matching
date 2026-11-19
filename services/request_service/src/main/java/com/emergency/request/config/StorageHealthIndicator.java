package com.emergency.request.config;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import com.emergency.common.storage.StorageClient;

/**
 * Reports the storage coordinator's readiness as the "storage" health component. It is
 * part of the readiness group (application.yml), so Docker/Kubernetes only send traffic
 * when storage can serve it, but not of liveness: restarting this service would not fix
 * storage.
 */
@Component("storage")
public class StorageHealthIndicator extends AbstractHealthIndicator {

    private final StorageClient storage;

    public StorageHealthIndicator(StorageClient storage) {
        this.storage = storage;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        if (storage.isReady()) {
            builder.up();
        } else {
            builder.down().withDetail("coordinator", "not ready");
        }
    }
}
