package com.thinkmorestupidless.ankka.javasdk.storage;

import java.time.Instant;

/** One object of a listing. */
public record ObjectEntry(String name, long length, Instant lastKept) {}
