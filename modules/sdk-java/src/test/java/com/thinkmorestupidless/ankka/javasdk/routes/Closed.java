package com.thinkmorestupidless.ankka.javasdk.routes;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

public class Closed extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.denyAll();
  }

  @Get("/closed")
  public String closed() {
    return "never";
  }
}
