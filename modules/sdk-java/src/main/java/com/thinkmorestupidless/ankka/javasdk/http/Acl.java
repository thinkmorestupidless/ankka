package com.thinkmorestupidless.ankka.javasdk.http;

import com.thinkmorestupidless.ankka.javasdk.bridge.Acls;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who may call an endpoint's routes. Every endpoint states one; there is no default, because an
 * endpoint that forgot would otherwise be open.
 */
public interface Acl {

  /** No caller. */
  static Acl denyAll() {
    return Acls.denyAll();
  }

  /** Any caller, the internet included. */
  static Acl allowAll() {
    return Acls.allowAll();
  }

  /** A caller the test admits. */
  static Acl allowIf(Predicate<Request> test) {
    return Acls.allowIf(test);
  }

  /**
   * A caller {@code decide} authenticates: {@link AuthDecision#allow} admits it and makes its
   * principal readable in the handler.
   */
  static Acl authenticate(Function<Request, AuthDecision> decide) {
    return Acls.authenticate(decide);
  }

  /** Only the named services of this project. */
  static Acl allowCallers(String... services) {
    return Acls.allowCallers(services);
  }

  /** A caller any of {@code acls} admits. */
  static Acl anyOf(Acl... acls) {
    return Acls.anyOf(acls);
  }

  /** Admits a caller presenting one of the service's client keys, issued for this endpoint. */
  static Acl clientKeys() {
    return Acls.clientKeys();
  }

  /**
   * Admits a caller presenting a token from one of the issuers the service's environment lists
   * ({@code ANKKA_AUTH_ISSUERS}); throws when it lists none.
   */
  static Acl oidc() {
    return Acls.oidc();
  }
}
