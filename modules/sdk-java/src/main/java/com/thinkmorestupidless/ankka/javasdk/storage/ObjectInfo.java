package com.thinkmorestupidless.ankka.javasdk.storage;

import java.time.Instant;

/** What a bucket knows of one object, without its body. */
public record ObjectInfo(String name, String contentType, long length, Instant lastKept) {}
