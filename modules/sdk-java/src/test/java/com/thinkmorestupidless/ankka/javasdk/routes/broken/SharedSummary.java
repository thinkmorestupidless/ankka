package com.thinkmorestupidless.ankka.javasdk.routes.broken;

import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar;
import java.util.List;

/** One scope's summary given to two routes declared in code. */
public class SharedSummary extends HttpEndpoint implements RouteRegistrar {
  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Override
  public List<Declaration> routes() {
    Description both =
        new Description(
            List.of("one summary"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    return List.of(
        new Declaration("GET", "/a", null, (r, p, b) -> null, List.of(both)),
        new Declaration("GET", "/b", null, (r, p, b) -> null, List.of(both)));
  }
}
