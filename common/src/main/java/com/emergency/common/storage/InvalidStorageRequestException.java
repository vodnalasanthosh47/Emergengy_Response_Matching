package com.emergency.common.storage;

/**
 * Coordinator 400: the key or record broke the data format (docs/data-format.md). This
 * is a bug in the calling service, not something a user can fix.
 */
public class InvalidStorageRequestException extends StorageException {
    public InvalidStorageRequestException(String message) {
        super(message);
    }
}
