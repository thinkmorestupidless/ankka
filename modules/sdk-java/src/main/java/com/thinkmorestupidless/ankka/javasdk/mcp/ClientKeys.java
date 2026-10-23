package com.thinkmorestupidless.ankka.javasdk.mcp;

import java.util.List;

/**
 * A service's client keys: the credentials it issues to the callers of its MCP endpoint, one per
 * caller. A key is shown once, in what {@code issue} answers, and never again. A refusal is a
 * {@code CommandException} naming what is wrong.
 */
public interface ClientKeys {

  /** A key for {@code subject}, for the service's one MCP endpoint, able to call every tool. */
  IssuedClientKey issue(String subject, Expiry expiry);

  IssuedClientKey issue(ClientKeyRequest request);

  /** Every key of the service, revoked ones included, never the key itself. */
  List<ClientKeySummary> list();

  /** Refused on its next request on every instance. An unknown id is not found. */
  void revoke(String id);
}
