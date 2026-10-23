package com.thinkmorestupidless.ankka.javasdk.client;

import java.util.Map;

/**
 * Calls to one other service over HTTP, as this service: mutual TLS on the platform, plain HTTP to
 * {@code ankka.local-services.<name>} on a developer's machine. A body and a reply are written and
 * read by the record codec. An answer that is not 2xx is a {@link ServiceCallFailed}; no answer is
 * a {@link ServiceUnreachable}.
 */
public interface ServiceClient {

  /** The service this client reaches, as named. */
  String target();

  /** Any request, answered as it came. */
  ServiceResponse request(String method, String path, byte[] body, String contentType);

  /** Any request with headers of its own, such as the caller's token to pass on. */
  ServiceResponse request(
      String method, String path, byte[] body, String contentType, Map<String, String> headers);

  <R> R get(String path, Class<R> reply);

  String getText(String path);

  /** As {@link #getText(String)}, with these headers sent beside the platform's own. */
  String getText(String path, Map<String, String> headers);

  <B, R> R post(String path, B body, Class<R> reply);

  <B, R> R put(String path, B body, Class<R> reply);

  void delete(String path);
}
