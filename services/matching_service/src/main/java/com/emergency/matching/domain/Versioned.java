package com.emergency.matching.domain;

/** A document as read from storage, with the version the next conditional write must name. */
public record Versioned<T>(T document, long version) {
}
