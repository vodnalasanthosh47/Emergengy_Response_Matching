package com.emergency.resource.config;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import com.emergency.common.storage.StorageClient;

/**
 * Coordinator readiness as the "storage" health component. Part of readiness only, not
 * liveness: restarting this service would not fix storage.
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
