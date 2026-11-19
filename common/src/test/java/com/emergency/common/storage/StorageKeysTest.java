package com.emergency.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageKeysTest {

    private static final String UUID = "8a7c3e0e-4c1d-4b9a-9d55-0d3c2f1a9b77";

    @Test
    void buildsKeysInTheDocumentedFormat() {
        assertThat(StorageKeys.request("f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d"))
                .isEqualTo("request:f3b1c2e4-0d1e-4a5b-9c8d-7e6f5a4b3c2d");
        assertThat(StorageKeys.resource("tdr1v9", UUID)).isEqualTo("resource:tdr1v9:" + UUID);
        assertThat(StorageKeys.resourceId("tdr1v9", UUID)).isEqualTo("res_tdr1v9_" + UUID);
    }

    @Test
    void resourceIdAndKeyConvertBothWays() {
        String id = "res_tdr1v9_" + UUID;
        String key = StorageKeys.resourceIdToKey(id);
        assertThat(key).isEqualTo("resource:tdr1v9:" + UUID);
        assertThat(StorageKeys.keyToResourceId(key)).isEqualTo(id);
    }

    @Test
    void rejectsWhatTheCoordinatorWouldReject() {
        assertThatThrownBy(() -> StorageKeys.request("a:b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.request("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.resource("smoke1", UUID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.resource("TDR1V9", UUID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.resource("tdr1v9", "a_b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.resourceIdToKey("tdr1v9_" + UUID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.resourceIdToKey("res_tdr1v9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.keyToResourceId("request:" + UUID)).isInstanceOf(IllegalArgumentException.class);
    }
}
