package com.thinkmorestupidless.ankka.javasdk.routes.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

public class Twice extends HttpEndpoint {
  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/x/{a}")
  public String first(String a) {
    return a;
  }

  @Get("/x/{b}")
  public String second(String b) {
    return b;
  }
}
