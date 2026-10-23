package com.thinkmorestupidless.ankka.javasdk.storage;

/** Who can reach a bucket from outside the service. */
public enum BucketExposure {
  /** Only the service. */
  PRIVATE,
  /** Anyone holding an address the service signed. */
  SIGNED,
  /** Anyone. */
  PUBLIC,
  /** A bucket the installation supplies, reached as it says. */
  SUPPLIED
}
