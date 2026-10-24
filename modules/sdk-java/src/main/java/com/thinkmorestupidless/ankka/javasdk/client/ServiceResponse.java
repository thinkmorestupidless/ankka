package com.thinkmorestupidless.ankka.javasdk.client;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** What another service answered. */
public record ServiceResponse(
    int status, String contentType, byte[] body, List<Map.Entry<String, String>> headers) {

  public String text() {
    return new String(body, StandardCharsets.UTF_8);
  }
}
