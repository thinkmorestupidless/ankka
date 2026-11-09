package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;

// docs:start endpoint
/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
public class CredentialsRoutes extends HttpEndpoint {

  public record KeepSecret(String name, String value) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Post("/credentials")
  public Done keep(@Body KeepSecret request) {
    secrets().put(request.name(), request.value());
    return Done.done();
  }

  // The name is a query parameter because it may hold a slash.
  @Get("/credentials")
  public String read() {
    var name = request().requiredQuery("name");
    return secrets()
        .get(name)
        .orElseThrow(() -> new ProblemException(Problem.notFound("no secret '" + name + "'")));
  }

  @Delete("/credentials")
  public Done remove() {
    secrets().delete(request().requiredQuery("name"));
    return Done.done();
  }
}
// docs:end endpoint
