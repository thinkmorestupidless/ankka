package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How many events an event sourced entity persists between snapshots: every 100 without it, and
 * never with {@code @SnapshotEvery(0)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SnapshotEvery {
  int value();
}
