package com.emergency.resource.repository;

import com.emergency.common.resource.ResourceDocument;
import com.emergency.common.resource.ResourceView;

/** A resource as read from storage, with the version needed for the next conditional write. */
public record StoredResource(ResourceDocument document, long version) {

    public ResourceView view() {
        return ResourceView.of(document, version);
    }
}
