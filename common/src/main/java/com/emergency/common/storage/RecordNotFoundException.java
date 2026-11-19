package com.emergency.common.storage;

/** Coordinator 404 on a delete: no such record. (A read of a missing key returns an empty Optional instead.) */
public class RecordNotFoundException extends StorageException {
    public RecordNotFoundException(String key) {
        super("record " + key + " not found");
    }
}
