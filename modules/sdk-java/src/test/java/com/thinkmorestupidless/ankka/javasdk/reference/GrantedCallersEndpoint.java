package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** Whoever the project granted this route to. */
public class GrantedCallersEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.granted());
  }

  @Get("/callers/granted")
  public String granted() {
    return "granted";
  }
}
