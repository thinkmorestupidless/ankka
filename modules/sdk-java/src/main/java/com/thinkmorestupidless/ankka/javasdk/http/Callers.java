package com.thinkmorestupidless.ankka.javasdk.http;

import com.thinkmorestupidless.ankka.javasdk.bridge.Acls;

/**
 * The callers an endpoint names: {@code Acl.allowCallers(Callers.internet(),
 * Callers.service("orders"))}. A request made on the same machine, with no certificate to read,
 * arrives as {@link Caller.Local}, which every one of them admits.
 */
public final class Callers {

  private Callers() {}

  /** The gateway, which is to say the internet, and a registered machine. */
  public static CallerMatcher internet() {
    return Acls.internet();
  }

  /** A service of this project. */
  public static CallerMatcher service(String name) {
    return Acls.service(name);
  }

  /** A service of another project. */
  public static CallerMatcher service(String project, String name) {
    return Acls.service(project, name);
  }

  /** Any service of this project. */
  public static CallerMatcher anyInProject() {
    return Acls.anyInProject();
  }

  /** This service itself. */
  public static CallerMatcher self() {
    return Acls.self();
  }

  /** Any service of any project. */
  public static CallerMatcher anyService() {
    return Acls.anyService();
  }

  /**
   * Whoever this service's project has granted the route or method to. A route that does not name
   * this can never be granted, whatever the project's owners do.
   */
  public static CallerMatcher granted() {
    return Acls.granted();
  }
}
