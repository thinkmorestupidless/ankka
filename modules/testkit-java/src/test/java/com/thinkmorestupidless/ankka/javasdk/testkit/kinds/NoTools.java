package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;

/** An MCP endpoint that declares nothing, which the start refuses. */
public class NoTools extends McpEndpoint {
  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }
}
