package com.thinkmorestupidless.ankka.javasdk.storage;

import java.util.List;

/**
 * The service's buckets in the installation's object store: the one it asks for in its descriptor,
 * and any it names. Where the service has none, asking for a bucket is a {@code CommandException}
 * with status 503 that says how to ask for one.
 */
public interface ObjectStorage {

  /** The bucket the service asks for with {@code provisionObjectStorage}. */
  Bucket defaultBucket();

  /** A bucket the service's descriptor names. */
  Bucket bucket(String name);

  List<Bucket> buckets();
}
