package com.thinkmorestupidless.ankka.javasdk.erasure;

import com.thinkmorestupidless.ankka.javasdk.storage.ErasedObjects;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What a run of the erasure handler erased: how many objects, and when they are gone for good —
 * at once when {@code finalAfter} is empty, or once it has passed, for a store that keeps deleted
 * objects for a while.
 */
public record ErasureReport(long objectsErased, Optional<Duration> finalAfter) {

  public ErasureReport {
    if (objectsErased < 0) throw new IllegalArgumentException("objectsErased is negative");
    Objects.requireNonNull(finalAfter, "finalAfter");
    if (finalAfter.isPresent() && finalAfter.get().isNegative())
      throw new IllegalArgumentException("finalAfter is negative");
  }

  /** {@code count} objects, gone for good now. */
  public static ErasureReport immediate(long count) {
    return new ErasureReport(count, Optional.empty());
  }

  /** {@code count} objects, gone for good once {@code within} has passed. */
  public static ErasureReport after(long count, Duration within) {
    return new ErasureReport(count, Optional.of(within));
  }

  /** Nothing erased. */
  public static ErasureReport nothing() {
    return immediate(0);
  }

  /** What {@code Bucket.erase} answered, as the handler's report. */
  public static ErasureReport of(ErasedObjects erased) {
    return new ErasureReport(erased.count(), erased.finalAfter());
  }
}
