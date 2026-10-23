package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** Says who called: admits the internet and the service {@code orders}. */
public class CallersEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.internet(), Callers.service("orders"));
  }

  @Get("/callers/whoami")
  public String whoami() {
    return Wire.callerWord(caller());
  }
}
