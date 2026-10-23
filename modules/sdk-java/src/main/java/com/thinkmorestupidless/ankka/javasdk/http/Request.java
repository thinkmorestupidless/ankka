package com.thinkmorestupidless.ankka.javasdk.http;

import java.util.List;
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

  Optional<String> header(String name);

  Caller caller();

  Optional<Principal> principal();
}
