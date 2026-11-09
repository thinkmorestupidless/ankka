package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.thinkmorestupidless.ankka.javasdk.storage.ObjectInfo;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import java.util.Optional;

/** The storage page's test, through the Java kit. */
public final class StorageChecks {

  private StorageChecks() {}

  // docs:start kit
  /** Seeds the store the service reads through the kit, and reads back what it holds. */
  static Optional<String> seeded(AnkkaTestKit kit) {
    // The Java kit declares no bucket of its own: a test seeds and reads the unnamed bucket the
    // service has (a named one comes from a descriptor the kit's settings point at).
    kit.storage().defaultBucket().putBytes("a/b.png", "png".getBytes(UTF_8), "image/png");
    return kit.storage().defaultBucket().stat("a/b.png").map(ObjectInfo::contentType);
  }
  // docs:end kit
}
