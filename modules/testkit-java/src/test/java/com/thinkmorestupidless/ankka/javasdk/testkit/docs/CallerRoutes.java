package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Caller;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

// docs:start allow-callers
/**
 * Only the internet and the orders service in this project; any other caller is refused 403. An
 * endpoint has one ACL for all its routes, so routes for another audience, such as another
 * instance of this very service and nothing else ({@code Callers.self()}), are an endpoint of
 * their own.
 */
public class CallerRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.internet(), Callers.service("orders"));
  }

  @Get("/callers/only-orders")
  public String onlyOrders() {
    return "admitted: " + describe(caller());
  }
  // docs:end allow-callers

  // docs:start who-is-calling
  /** The caller is always present: the platform established it before any ACL ran. */
  @Get("/callers/whoami")
  public String whoami() {
    return describe(caller());
  }

  static String describe(Caller caller) {
    return switch (caller) {
      case Caller.Gateway g -> "the internet";
      case Caller.Service s -> "service " + s.project() + "/" + s.name();
      case Caller.Machine m -> "machine " + m.organization() + "/" + m.name();
      case Caller.Local l -> "this machine";
    };
  }
  // docs:end who-is-calling
}
