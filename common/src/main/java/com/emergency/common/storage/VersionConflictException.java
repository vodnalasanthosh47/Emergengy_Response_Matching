package com.emergency.common.storage;

/**
 * Coordinator 409: {@code expectedVersion} did not match (someone else changed the
 * record first), or {@code expectedVersion 0} and the key already exists. Re-read the
 * record and decide again.
 */
public class VersionConflictException extends StorageException {
    public VersionConflictException(String key) {
        super("record " + key + " changed or already exists");
    }
}
