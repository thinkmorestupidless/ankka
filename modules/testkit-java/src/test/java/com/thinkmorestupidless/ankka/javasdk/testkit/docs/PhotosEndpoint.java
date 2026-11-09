package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.StreamBody;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;
import com.thinkmorestupidless.ankka.javasdk.http.Response;
import com.thinkmorestupidless.ankka.javasdk.http.StreamedBody;
import com.thinkmorestupidless.ankka.javasdk.storage.Bucket;
import com.thinkmorestupidless.ankka.javasdk.storage.SignedAccess;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

// docs:start endpoint
/** An endpoint over the service's buckets: keep, read, describe, list, delete, sign and address. */
public class PhotosEndpoint extends HttpEndpoint {

  public record KeepObject(String name, String text, String contentType) {}

  public record Described(String name, String contentType, long length, String lastKept) {}

  public record ListedObject(String name, long length, String lastKept) {}

  public record Listed(List<ListedObject> objects) {}

  public record RecordPhoto(String name, String text, String contentType) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  /** The bucket the request names, or the service's unnamed one. */
  private Bucket bucket() {
    return request().query("bucket").map(storage()::bucket).orElseGet(storage()::defaultBucket);
  }

  @Post("/storage/objects")
  public Done keep(@Body KeepObject request) {
    bucket().putBytes(request.name(), request.text().getBytes(UTF_8), request.contentType());
    return Done.done();
  }

  // An upload (a POST) passed to the bucket as it arrives, never held whole: any size the route
  // admits.
  @StreamBody("/storage/upload")
  public Done upload(StreamedBody body) {
    var length =
        body.length()
            .orElseThrow(
                () -> new ProblemException(Problem.badRequest("an upload declares its length")));
    bucket().put(request().requiredQuery("name"), body.body(), length, body.contentType());
    return Done.done();
  }

  // A download. A Java route answers its body whole, so the object is read before it is sent.
  @Get("/storage/objects")
  public Response object() throws IOException {
    var name = request().requiredQuery("name");
    try (var object = bucket().get(name).orElseThrow(() -> noObject(name))) {
      return Response.bytes(object.body().readAllBytes(), object.contentType());
    }
  }

  @Get("/storage/stat")
  public Described stat() {
    var name = request().requiredQuery("name");
    var info = bucket().stat(name).orElseThrow(() -> noObject(name));
    var kept = info.lastKept().toString();
    return new Described(info.name(), info.contentType(), info.length(), kept);
  }

  @Get("/storage/list")
  public Listed list() {
    var prefix = request().query("prefix").orElse("");
    return new Listed(
        bucket().list(prefix).stream()
            .map(e -> new ListedObject(e.name(), e.length(), e.lastKept().toString()))
            .toList());
  }

  @Delete("/storage/objects")
  public Done delete() {
    bucket().delete(request().requiredQuery("name"));
    return Done.done();
  }

  @Get("/storage/signed")
  public String signed() {
    var name = request().requiredQuery("name");
    var access = request().query("access").filter("keep"::equals).isPresent()
        ? SignedAccess.KEEP
        : SignedAccess.READ;
    var span = request().query("seconds").map(s -> Duration.ofSeconds(Long.parseLong(s)));
    return span.map(d -> bucket().signedUrl(name, access, d))
        .orElseGet(() -> bucket().signedUrl(name, access))
        .toString();
  }

  @Get("/storage/url")
  public String url() {
    return bucket().url(request().requiredQuery("name")).toString();
  }

  // The entity keeps the name; the bucket keeps the object.
  @Post("/storage/listings/{id}/photos")
  public Done recordPhoto(String id, @Body RecordPhoto request) {
    storage()
        .defaultBucket()
        .putBytes(request.name(), request.text().getBytes(UTF_8), request.contentType());
    return componentClient()
        .forEventSourcedEntity(id)
        .call(Listing::recordPhoto)
        .invoke(request.name());
  }

  private static ProblemException noObject(String name) {
    return new ProblemException(Problem.notFound("no object '" + name + "'"));
  }
}
// docs:end endpoint
