package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectEntry;
import com.thinkmorestupidless.ankka.javasdk.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** An endpoint over the service's secrets, its bucket and another service. */
public class Vault extends HttpEndpoint {

  public record Value(String value) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Post("/vault/secrets/{name}")
  public Done keep(String name, @Body Value value) {
    secrets().put(name, value.value());
    return Done.done();
  }

  @Get("/vault/secrets/{name}")
  public Value secret(String name) {
    return new Value(secrets().get(name).orElse("(none)"));
  }

  /** Streams the body to the bucket with its length, as a large upload would be. */
  @Post("/vault/objects/{name}")
  public Done put(String name, @Body Value value) {
    byte[] bytes = value.value().getBytes(StandardCharsets.UTF_8);
    storage()
        .defaultBucket()
        .put(name, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
    return Done.done();
  }

  /** Reads the object back as a stream, closed when read. */
  @Get("/vault/objects/{name}")
  public Value object(String name) throws IOException {
    var found = storage().defaultBucket().get(name);
    if (found.isEmpty()) return new Value("(none)");
    try (StoredObject object = found.get()) {
      return new Value(
          object.contentType() + ":" + new String(object.body().readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Get("/vault/objects")
  public List<String> names() {
    return storage().defaultBucket().list("").stream().map(ObjectEntry::name).toList();
  }

  /** Calls the stock service with a token of its own. */
  @Get("/vault/stock/{sku}")
  public Value stock(String sku) {
    var response =
        services()
            .of("stock")
            .request("GET", "/stock/" + sku, null, null, Map.of("Authorization", "Bearer vault"));
    return new Value(response.status() + " " + response.text());
  }
}
