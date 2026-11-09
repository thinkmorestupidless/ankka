package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** Another instance of this very service, and nothing else. */
public class SelfRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.self());
  }

  @Get("/callers/only-self")
  public String onlySelf() {
    return "admitted: myself";
  }
}
