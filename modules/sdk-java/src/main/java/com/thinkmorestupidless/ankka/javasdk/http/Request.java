package com.thinkmorestupidless.ankka.javasdk.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The request being handled. Readable on the handler's own thread only: read what you need before
 * handing work to another.
 */
public interface Request {

  String method();

  String path();

  Optional<String> query(String name);

  List<String> queryAll(String name);

  /**
   * A query parameter the handler cannot proceed without; its absence answers 400 naming it.
   */
  String requiredQuery(String name);

  /**
   * Whether a flag is set: present with no value counts as {@code true}, so {@code ?verbose} and
   * {@code ?verbose=true} agree.
   */
  boolean flag(String name);

  Optional<String> header(String name);

  /** Every header the request carries, in order, a name as many times as it was sent. */
  List<Map.Entry<String, String>> headers();

  Caller caller();

  Optional<Principal> principal();
}
