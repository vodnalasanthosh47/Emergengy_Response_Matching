package com.emergency.common.storage;

/** Coordinator 412: a conditional write ({@code expectedVersion >= 1}) to a key that does not exist. */
public class PreconditionFailedException extends StorageException {
    public PreconditionFailedException(String key) {
        super("record " + key + " does not exist");
    }
}
