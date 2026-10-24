package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Caller;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket;
import com.thinkmorestupidless.ankka.javasdk.http.SocketClosed;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Routes admitting the orders service and the internet only, by caller matchers. */
public class Gate extends HttpEndpoint {

  /** How the last socket on {@code /gate/late} was closed when its handler sent too late. */
  public static final AtomicReference<String> lateReason = new AtomicReference<>();

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.service("orders"), Callers.internet());
  }

  @Get("/gate/who")
  public String who() {
    var who =
        switch (caller()) {
          case Caller.Service s -> s.project() + "/" + s.name();
          case Caller.Gateway g -> "the internet";
          case Caller.Machine m -> m.organization() + "/" + m.name();
          case Caller.Local l -> "this machine";
        };
    return request().flag("loud") ? who.toUpperCase() : who;
  }

  @Get("/gate/need")
  public String need() {
    return "got " + request().requiredQuery("q");
  }

  @Socket("/gate/shout")
  public void shout(WebSocket socket) {
    Optional<String> frame;
    while ((frame = socket.receive()).isPresent()) socket.send(frame.get().toUpperCase());
  }

  @Socket("/gate/late")
  public void late(WebSocket socket) {
    while (socket.receive().isPresent()) {}
    try {
      socket.send("too late");
    } catch (SocketClosed closed) {
      lateReason.set(closed.reason());
    }
  }
}
