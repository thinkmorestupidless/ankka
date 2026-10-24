package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.bridge.TestAcls;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Principal;
import com.thinkmorestupidless.ankka.javasdk.mcp.McpEndpoint;

/**
 * Tools for people the service signs in itself. The ACL is the suite's test issuer, as {@code
 * Acl.oidc()} would read it from {@code ANKKA_AUTH_ISSUERS}.
 */
public class SignedInTools extends McpEndpoint {

  public SignedInTools() {
    tool("whoami")
        .describedAs("Says who signed in")
        .handle(() -> principal().map(Principal::subject).orElse("nobody"));
  }

  @Override
  protected Acl acl() {
    return TestAcls.tokens();
  }

  @Override
  protected boolean brokered() {
    return true;
  }
}
