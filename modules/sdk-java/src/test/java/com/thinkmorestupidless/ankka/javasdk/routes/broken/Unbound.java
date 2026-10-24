package com.thinkmorestupidless.ankka.javasdk.routes.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.*;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.routes.Thing;

public class Unbound extends HttpEndpoint {
  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/x/{a}")
  public String m() { return ""; }
}
