package com.emergency.request.domain;

import com.emergency.common.request.RequestDocument;
import com.emergency.common.request.RequestView;

/** A request as read from storage, with the version needed for the next conditional write. */
public record StoredRequest(RequestDocument document, long version) {

    public RequestView view() {
        return RequestView.of(document, version);
    }
}
