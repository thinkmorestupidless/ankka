package com.thinkmorestupidless.ankka.javasdk.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * What {@link Bucket#erase} removed: how many objects, and when they are gone for good — now when
 * {@code finalAfter} is empty, or once it has passed, on a store that keeps a deleted object for a
 * soft-delete window.
 */
public record ErasedObjects(long count, Optional<Duration> finalAfter) {}
