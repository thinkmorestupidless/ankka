package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeyRequest;
import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeySummary;
import com.thinkmorestupidless.ankka.javasdk.mcp.Expiry;
import com.thinkmorestupidless.ankka.javasdk.mcp.IssuedClientKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The reference's own routes over its client keys, as a product would write them. */
public class McpKeysEndpoint extends HttpEndpoint {

  /** What a post is asked to issue: a key that never expires, for the cases. */
  @Manifest("mcp-key-request")
  public record McpKeyRequest(
      String subject, Optional<Set<String>> scope, Optional<Map<String, String>> attributes) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Post("/mcp-keys")
  public IssuedClientKey issue(@Body McpKeyRequest body) {
    return clientKeys()
        .issue(
            new ClientKeyRequest(
                body.subject(),
                Expiry.never(),
                Optional.empty(),
                body.scope().orElse(Set.of()),
                body.attributes().orElse(Map.of()),
                Optional.empty()));
  }

  @Get("/mcp-keys")
  public List<ClientKeySummary> list() {
    return clientKeys().list();
  }

  @Delete("/mcp-keys/{id}")
  public String revoke(String id) {
    clientKeys().revoke(id);
    return "revoked";
  }
}
