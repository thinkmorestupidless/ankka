package com.thinkmorestupidless.ankka.javasdk.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/** An object read from a bucket, its body open until closed. */
public record StoredObject(String contentType, long length, Instant lastKept, InputStream body)
    implements AutoCloseable {

  @Override
  public void close() throws IOException {
    body.close();
  }
}
