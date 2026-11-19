package com.emergency.common.request;

/** Who asked for help. Optional on a request. */
public record Requester(String name, String contact) {
}
