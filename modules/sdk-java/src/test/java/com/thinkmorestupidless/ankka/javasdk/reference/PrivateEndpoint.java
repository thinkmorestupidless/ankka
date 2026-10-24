package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Principal;
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket;

/** Admits only a verified token from the suite's test issuer, and says what its principal carried. */
public class PrivateEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return JavaReference.authenticated();
  }

  private Principal who() {
    return principal().orElseThrow(() -> new IllegalStateException("no principal"));
  }

  @Get("/private/")
  public String root() {
    return "private";
  }

  @Get("/private/me")
  public String me() {
    var p = who();
    return "{\"subject\":"
        + Wire.quoted(p.subject())
        + ",\"roles\":"
        + Wire.sortedArray(p.roles())
        + ",\"tier\":"
        + Wire.quotedOrNull(p.claims().get("tier"))
        + ",\"issuer\":"
        + Wire.quotedOrNull(p.issuer().orElse(null))
        + "}";
  }

  // The principal and the caller, sent once when the socket opens.
  @Socket("/private/socket")
  public void socket(WebSocket socket) {
    var p = who();
    ConformanceEndpoint.SOCKET_LOG.add("private:" + p.subject());
    socket.send(
        "{\"subject\":"
            + Wire.quoted(p.subject())
            + ",\"roles\":"
            + Wire.sortedArray(p.roles())
            + ",\"caller\":"
            + Wire.quoted(Wire.callerWord(caller()))
            + "}");
    while (socket.receive().isPresent()) {}
  }
}
