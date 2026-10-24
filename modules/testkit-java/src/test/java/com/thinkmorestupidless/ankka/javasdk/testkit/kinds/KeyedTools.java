package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Principal;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;

/** Tools only a client key admits, at a path of their own. */
public class KeyedTools extends McpEndpoint {

  public KeyedTools() {
    super("/keyed");
    tool("whoami")
        .describedAs("Says whose key called")
        .handle(() -> principal().map(Principal::subject).orElse("nobody"));
    tool("other").describedAs("Another tool, outside a narrow key's scope").handle(() -> "other");
  }

  @Override
  protected Acl acl() {
    return Acl.clientKeys();
  }
}
