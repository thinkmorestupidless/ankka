package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/**
 * A route whose ACL differs from the rest of {@code /conformance}: it admits nobody. Java gives an
 * ACL to a class, so the route is a class of its own.
 */
public class ClosedEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.denyAll();
  }

  @Get("/conformance/closed")
  public String closed() {
    return "never reached";
  }
}
