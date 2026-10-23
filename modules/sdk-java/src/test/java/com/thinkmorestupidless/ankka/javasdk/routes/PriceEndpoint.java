package com.thinkmorestupidless.ankka.javasdk.routes;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceCallFailed;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** Asks another service, "pricing", on behalf of its caller. */
public class PriceEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/price/{sku}")
  public Thing price(String sku) {
    return services().of("pricing").get("/prices/" + sku, Thing.class);
  }

  @Post("/quote/{sku}")
  public String quote(String sku) {
    try {
      return services().of("pricing").post("/quotes", new Thing(sku, 1), Thing.class).id();
    } catch (ServiceCallFailed e) {
      return "pricing answered " + e.status();
    }
  }
}
