package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.util.stream.Stream;

/** The {@code /callers} routes only the service itself may call. */
public class SelfCallersEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.self());
  }

  @Get("/callers/self")
  public String self() {
    return "self";
  }

  @Sse("/callers/events")
  public Stream<SseEvent> events() {
    return Stream.of(SseEvent.text("tick"));
  }
}
