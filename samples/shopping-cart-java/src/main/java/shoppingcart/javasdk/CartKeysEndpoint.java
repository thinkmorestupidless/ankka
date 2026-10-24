package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeyRequest;
import com.thinkmorestupidless.ankka.javasdk.mcp.Expiry;
import com.thinkmorestupidless.ankka.javasdk.mcp.IssuedClientKey;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Issues and revokes the keys a customer's assistant carries to the cart's tools. Open to anyone,
 * as the Scala sample's is: a real product puts this behind its own sign-in.
 */
public class CartKeysEndpoint extends HttpEndpoint {

  @Manifest("key-request")
  public record KeyRequest(String subject, Optional<Set<String>> scope, Optional<String> label) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Post("/mcp-keys")
  public IssuedClientKey issue(@Body KeyRequest asked) {
    return clientKeys()
        .issue(
            new ClientKeyRequest(
                asked.subject(),
                Expiry.after(Duration.ofDays(365)),
                Optional.empty(),
                asked.scope().orElse(Set.of()),
                Map.of(),
                asked.label()));
  }

  @Delete("/mcp-keys/{id}")
  public String revoke(String id) {
    clientKeys().revoke(id);
    return "revoked";
  }
}
