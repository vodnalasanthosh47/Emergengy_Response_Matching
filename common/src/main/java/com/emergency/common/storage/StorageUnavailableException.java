package com.emergency.common.storage;

/**
 * The storage tier could not be reached, timed out, or answered 503/5xx. For a write the
 * outcome is unknown: re-read the record before retrying. Services answer 503.
 */
public class StorageUnavailableException extends StorageException {
    public StorageUnavailableException(String message) {
        super(message);
    }

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
