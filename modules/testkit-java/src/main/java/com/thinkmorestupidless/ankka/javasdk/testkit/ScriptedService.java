package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.client.ServiceResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Another service, played on loopback: it records what it is sent and answers as told. */
public interface ScriptedService {

  /** One request it was sent. */
  record Request(String method, String path, String body, Map<String, String> headers) {}

  /** How it answers every request from now on. */
  void answer(Function<Request, ServiceResponse> answer);

  /** Answers the next request with {@code status}, then as before. */
  void failNext(int status);

  /** Every request it was sent, oldest first. */
  List<Request> requests();

  void clear();
}
