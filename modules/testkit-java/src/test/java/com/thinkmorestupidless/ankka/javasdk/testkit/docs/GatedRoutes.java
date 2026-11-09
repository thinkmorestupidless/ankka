package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

// docs:start gated
/** An endpoint whose ACL inspects the request: the same request the handler sees. */
public class GatedRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowIf(
        request ->
            request.header("X-Api-Key").filter("let-me-in"::equals).isPresent()
                || request.flag("public"));
  }

  @Get("/gated")
  public String gated() {
    return "allowed";
  }
}
// docs:end gated
