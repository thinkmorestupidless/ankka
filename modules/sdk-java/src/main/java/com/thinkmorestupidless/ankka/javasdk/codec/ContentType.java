package com.thinkmorestupidless.ankka.javasdk.codec;

/** How a payload's bytes are read, as the platform's encoding names them. */
public enum ContentType {
  /** {@code application/json}: records, sum types, enums, collections. */
  JSON("application/json"),
  /** {@code text/plain}: a top-level string, number, boolean or duration. */
  TEXT("text/plain"),
  /** {@code application/octet-stream}: {@code Done}, nothing, bytes, an optional value. */
  BINARY("application/octet-stream");

  private final String mediaType;

  ContentType(String mediaType) {
    this.mediaType = mediaType;
  }

  /** The media type the platform's encoding gives. */
  public String mediaType() {
    return mediaType;
  }
}
