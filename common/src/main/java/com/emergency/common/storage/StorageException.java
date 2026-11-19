package com.emergency.common.storage;

/** Base class of the errors {@link StorageClient} reports; each maps to one coordinator status. */
public abstract class StorageException extends RuntimeException {
    protected StorageException(String message) {
        super(message);
    }

    protected StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
