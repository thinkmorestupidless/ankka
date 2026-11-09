package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import java.util.TreeSet;
import java.util.function.Supplier;

// docs:start oidc
/** An endpoint whose users sign in with an identity provider the service lists. */
public class AccountRoutes extends HttpEndpoint {

  /** The issuers the service's environment lists; a test holds its own issuer's rule here. */
  static Supplier<Acl> rule = Acl::oidc;

  @Override
  protected Acl acl() {
    return rule.get();
  }

  @Get("/account/me")
  public String me() {
    var principal = principal().orElseThrow();
    return principal.subject()
        + " from "
        + principal.issuer().orElse("?")
        + " roles="
        + String.join(",", new TreeSet<>(principal.roles()))
        + " tier="
        + principal.claims().getOrDefault("tier", "");
  }
}
// docs:end oidc
