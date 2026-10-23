package com.thinkmorestupidless.ankka.javasdk.client;

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

  <R> R get(String path, Class<R> reply);

  String getText(String path);

  <B, R> R post(String path, B body, Class<R> reply);

  <B, R> R put(String path, B body, Class<R> reply);

  void delete(String path);
}
