package com.thinkmorestupidless.ankka.javasdk.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * One bucket. A put streams its body to the store once, never holding it whole; a get answers a
 * stream the caller closes, through {@link StoredObject}.
 */
public interface Bucket {

  /** The bucket's name in the descriptor; empty for the service's own. */
  Optional<String> name();

  BucketExposure exposure();

  void put(String name, InputStream body, long length, String contentType);

  void putBytes(String name, byte[] body, String contentType);

  /** The object, its body open until closed; close it, with try-with-resources. */
  Optional<StoredObject> get(String name);

  /** The object's body, read whole. */
  Optional<byte[]> getBytes(String name);

  void delete(String name);

  /** Every object whose name starts with {@code prefix}, in name order. */
  List<ObjectEntry> list(String prefix);

  Optional<ObjectInfo> stat(String name);

  /** An address that reads, or keeps, {@code name} for {@code span}, for a signed bucket. */
  URI signedUrl(String name, SignedAccess access, Duration span);

  /** {@link #signedUrl(String, SignedAccess, Duration)} for the store's default span. */
  URI signedUrl(String name, SignedAccess access);

  /** The unsigned address of {@code name} in a public bucket. */
  URI url(String name);
}
