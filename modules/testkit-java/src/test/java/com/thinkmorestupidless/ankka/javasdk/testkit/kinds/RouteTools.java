package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;

/** Tools that name routes of the cart's HTTP endpoint. */
public class RouteTools extends McpEndpoint {

  public RouteTools() {
    super("/routes");
    tool("read_cart").describedAs("Reads a cart by its route").route("", "GET", "/carts/{id}");
    tool("add_by_route")
        .describedAs("Adds an item by its route")
        .route("", "POST", "/carts/{id}/items");
  }

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }
}
