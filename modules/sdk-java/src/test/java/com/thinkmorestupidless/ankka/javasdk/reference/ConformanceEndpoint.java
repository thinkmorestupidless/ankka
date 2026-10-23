package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.client.CommandException;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceCallFailed;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceIdentityMismatch;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceUnanswered;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceUnresolvable;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket;
import com.thinkmorestupidless.ankka.javasdk.storage.Bucket;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectEntry;
import com.thinkmorestupidless.ankka.javasdk.storage.SignedAccess;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Stream;

/** The protocol's edge cases as routes, the Scala reference's {@code /conformance} route for route. */
public class ConformanceEndpoint extends HttpEndpoint {

  /**
   * What the socket routes' handlers noticed: {@code closed:<room>} when a room's socket closed, and
   * {@code private:<subject>} when the authenticated one ran.
   */
  static final ConcurrentLinkedQueue<String> SOCKET_LOG = new ConcurrentLinkedQueue<>();

  @Manifest("echo")
  public record Echo(List<String> a, java.util.Optional<String> b, Map<String, String> headers) {}

  /** What a call to another service came to, the same in every language. */
  @Manifest("service-call-record")
  public record ServiceCallRecord(
      String outcome, int status, String contentType, String body, String answer, String message) {
    static ServiceCallRecord raised(String outcome, String message) {
      return new ServiceCallRecord(outcome, 0, "", "", "", message);
    }
  }

  /** What a storage call did, the same in every language. */
  @Manifest("storage-record")
  public record StorageRecord(
      String outcome,
      String code,
      String message,
      String contentType,
      String text,
      long length,
      List<String> names,
      String url) {
    static StorageRecord of(String outcome) {
      return new StorageRecord(outcome, "", "", "", "", 0, List.of(), "");
    }

    static StorageRecord refused(String code, String message) {
      return new StorageRecord("refused", code, message, "", "", 0, List.of(), "");
    }

    StorageRecord withObject(String type, String body, long size) {
      return new StorageRecord(outcome, code, message, type, body, size, names, url);
    }

    StorageRecord withNames(List<String> listed) {
      return new StorageRecord(outcome, code, message, contentType, text, length, listed, url);
    }

    StorageRecord withUrl(String address) {
      return new StorageRecord(outcome, code, message, contentType, text, length, names, address);
    }
  }

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private EntityRef entity(String id) {
    return componentClient().forEventSourcedEntity(id);
  }

  private EntityRef profile(String id) {
    return componentClient().forKeyValueEntity(id);
  }

  /** What a sidecar reported through its errors; nothing in process. */
  @Get("/conformance/problems")
  public List<String> problems() {
    return List.of();
  }

  // A call to another service, as the case asks for it: `service`, `method` and `path` from the
  // query, the body and every `X-Conformance-*` header sent on, and two headers no handler may
  // send, added to show they never arrive.
  @Post("/conformance/service-call")
  public ServiceCallRecord serviceCall(@Body String body) {
    var service = request().requiredQuery("service");
    var method = request().requiredQuery("method");
    var path = request().requiredQuery("path");
    var headers = new LinkedHashMap<String, String>();
    for (var h : request().headers())
      if (h.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("x-conformance-"))
        headers.put(h.getKey(), h.getValue());
    headers.put("X-Ankka-Caller", "ankka://elsewhere/impostor");
    headers.put("Host", "elsewhere");
    var client = services().of(service);
    try {
      if (request().query("mode").filter("typed"::equals).isPresent())
        return new ServiceCallRecord("response", 200, "", client.getText(path, headers), "", "");
      var answer =
          client.request(
              method,
              path,
              body.isEmpty() ? null : body.getBytes(StandardCharsets.UTF_8),
              body.isEmpty() ? null : request().header("Content-Type").orElse(null),
              headers);
      var named =
          answer.headers().stream()
              .filter(h -> h.getKey().equalsIgnoreCase("x-answer"))
              .map(Map.Entry::getValue)
              .findFirst()
              .orElse("");
      return new ServiceCallRecord(
          "response", answer.status(), answer.contentType(), answer.text(), named, "");
    } catch (ServiceCallFailed e) {
      return new ServiceCallRecord("failed", e.status(), "", e.body(), "", "");
    } catch (ServiceUnresolvable e) {
      return ServiceCallRecord.raised("unresolvable", e.getMessage());
    } catch (ServiceIdentityMismatch e) {
      return ServiceCallRecord.raised("mismatch", e.getMessage());
    } catch (ServiceUnanswered e) {
      return ServiceCallRecord.raised("unanswered", e.getMessage());
    } catch (CommandException e) {
      return ServiceCallRecord.raised("refused", e.problem().detail());
    }
  }

  // The secret store. The name is a query parameter because it may hold a slash.
  @Post("/conformance/secrets")
  public Done putSecret(@Body String value) {
    secrets().put(request().requiredQuery("name"), value);
    return Done.done();
  }

  @Get("/conformance/secrets")
  public String getSecret() {
    var name = request().requiredQuery("name");
    return secrets()
        .get(name)
        .orElseThrow(() -> new ProblemException(Problem.notFound("no secret '" + name + "'")));
  }

  @Delete("/conformance/secrets")
  public Done deleteSecret() {
    secrets().delete(request().requiredQuery("name"));
    return Done.done();
  }

  // The service's buckets. `bucket` names one (absent: the unnamed bucket) and `name` the object;
  // `op` is get, stat, list (under `prefix`), url or signed-url (`access` read or keep). A post
  // keeps its body under `name`; a post with `rules` set to `name` or `prefix` instead tries its
  // body as an object's name or a listing's prefix, and keeps nothing.
  @Post("/conformance/storage")
  public StorageRecord keep(@Body String body) {
    return storageCall(
        bucket -> {
          var rules = request().query("rules");
          if (rules.isPresent()) {
            if (rules.get().equals("prefix")) bucket.list(body);
            else bucket.stat(body);
          } else {
            bucket.putBytes(
                request().requiredQuery("name"),
                body.getBytes(StandardCharsets.UTF_8),
                request().header("Content-Type").orElse("application/octet-stream"));
          }
          return StorageRecord.of("ok");
        });
  }

  @Get("/conformance/storage")
  public StorageRecord read() {
    return storageCall(
        bucket -> {
          var name = request().query("name").orElse("");
          var op = request().requiredQuery("op");
          return switch (op) {
            case "get" ->
                bucket
                    .getBytes(name)
                    .map(
                        bytes -> {
                          var type =
                              bucket.stat(name).map(i -> i.contentType()).orElse("");
                          return StorageRecord.of("ok")
                              .withObject(
                                  type, new String(bytes, StandardCharsets.UTF_8), bytes.length);
                        })
                    .orElse(StorageRecord.of("absent"));
            case "stat" ->
                bucket
                    .stat(name)
                    .map(i -> StorageRecord.of("ok").withObject(i.contentType(), "", i.length()))
                    .orElse(StorageRecord.of("absent"));
            case "list" -> {
              var prefix = request().query("prefix").orElse("");
              yield StorageRecord.of("ok")
                  .withNames(bucket.list(prefix).stream().map(ObjectEntry::name).toList());
            }
            case "url" -> StorageRecord.of("ok").withUrl(bucket.url(name).toString());
            case "signed-url" -> {
              var access =
                  request().query("access").filter("keep"::equals).isPresent()
                      ? SignedAccess.KEEP
                      : SignedAccess.READ;
              yield StorageRecord.of("ok").withUrl(bucket.signedUrl(name, access).toString());
            }
            default -> StorageRecord.refused("BAD_REQUEST", "no storage op '" + op + "'");
          };
        });
  }

  @Delete("/conformance/storage")
  public StorageRecord remove() {
    return storageCall(
        bucket -> {
          bucket.delete(request().requiredQuery("name"));
          return StorageRecord.of("ok");
        });
  }

  /** The bucket the request names, and every refusal as a record rather than a status. */
  private StorageRecord storageCall(Function<Bucket, StorageRecord> f) {
    try {
      var named = request().query("bucket");
      return f.apply(
          named.isPresent() ? storage().bucket(named.get()) : storage().defaultBucket());
    } catch (CommandException e) {
      return StorageRecord.refused(codeWord(e.problem()), e.problem().detail());
    }
  }

  /** A refusal's code as the protocol says it: {@code BAD_REQUEST}. */
  private static String codeWord(Problem problem) {
    return switch (problem.status()) {
      case 400 -> "BAD_REQUEST";
      case 401 -> "UNAUTHORIZED";
      case 403 -> "FORBIDDEN";
      case 404 -> "NOT_FOUND";
      case 409 -> "CONFLICT";
      case 503 -> "UNAVAILABLE";
      case 504 -> "TIMEOUT";
      default -> "INTERNAL";
    };
  }

  @Get("/conformance/echo")
  public Echo echo() {
    return new Echo(
        request().queryAll("a"),
        request().query("b"),
        Map.of(
            "x-one", request().header("x-one").orElse(""),
            "x-two", request().header("x-two").orElse("")));
  }

  @Get("/conformance/status/{code}")
  public String status(int code) {
    throw new ProblemException(new Problem(code, "", "status " + code + " as asked"));
  }

  @Get("/conformance/boom")
  public String boom() {
    throw new RuntimeException("boom from the handler");
  }

  @Sse("/conformance/stream/{session}")
  public Stream<SseEvent> stream(String session) {
    return Stream.of(" leading space", "two\nlines", "plain").map(SseEvent::text);
  }

  // Echoes each frame; "context" is answered with the room and the opening request's `tag`.
  @Socket("/conformance/socket/{room}")
  public void socket(String room, WebSocket socket) {
    for (var frame = socket.receive(); frame.isPresent(); frame = socket.receive()) {
      if (frame.get().equals("context"))
        socket.send(room + " " + request().query("tag").orElse(""));
      else socket.send(frame.get());
    }
    SOCKET_LOG.add("closed:" + room);
  }

  @Socket("/conformance/socket-once")
  public void socketOnce(WebSocket socket) {
    socket.receive();
  }

  @Socket("/conformance/socket-fail")
  public void socketFail(WebSocket socket) {
    socket.receive();
    throw new RuntimeException("the socket handler broke");
  }

  @Get("/conformance/socket-log")
  public List<String> socketLog() {
    return List.copyOf(SOCKET_LOG);
  }

  @Post("/conformance/profile/{id}")
  public String setProfile(String id, @Body String name) {
    return profile(id).call(Profile::set).invoke(name);
  }

  @Get("/conformance/profile/{id}")
  public String getProfile(String id) {
    return profile(id).call(Profile::get).invoke();
  }

  @Delete("/conformance/profile/{id}")
  public String deleteProfile(String id) {
    return profile(id).call(Profile::delete).invoke();
  }

  @Post("/conformance/checkout/{id}")
  public String startCheckout(String id, @Body String mode) {
    return componentClient().forWorkflow(id).call(Checkout::start).invoke(mode);
  }

  @Get("/conformance/checkout/{id}")
  public String checkoutStatus(String id) {
    return componentClient().forWorkflow(id).call(Checkout::status).invoke();
  }

  @Get("/conformance/checkout/{id}/row")
  public CheckoutRows.CheckoutRow checkoutRow(String id) {
    return viewClient()
        .forView(CheckoutRows.class)
        .get(id)
        .orElseThrow(() -> new ProblemException(Problem.notFound("no row for '" + id + "'")));
  }

  @Get("/conformance/checkout-rows/{standing}")
  public List<String> checkoutRows(String standing) {
    return viewClient()
        .forView(CheckoutRows.class)
        .ask(CheckoutRows.BY_STANDING, Map.of("standing", standing))
        .stream()
        .map(CheckoutRows.CheckoutRow::id)
        .toList();
  }

  @Get("/conformance/checkout/{id}/end")
  public String checkoutEnd(String id) {
    var end = profile("end-" + id).call(Profile::get).invoke();
    if (end.equals("none"))
      throw new ProblemException(Problem.notFound("'" + id + "' has not ended"));
    return end;
  }

  // Starts a checkout and answers with how it ended, as one request; a failed one is 424.
  @Post("/conformance/checkout-await/{id}")
  public String checkoutAwait(String id, @Body String mode) {
    try {
      return componentClient()
          .forWorkflow(id)
          .callThenAwaitEnd(Checkout::start, Duration.ofSeconds(30))
          .invoke(mode)
          .status();
    } catch (CommandException failed) {
      var end = failed.workflowFailure();
      if (end.isEmpty()) throw failed;
      throw new ProblemException(
          new Problem(424, "", end.get().step().orElse("") + ": " + end.get().reason()));
    }
  }

  @Post("/conformance/remind/{id}")
  public Done remind(String id) {
    timers().schedule("remind-" + id, Duration.ofSeconds(1), Reminder::remind, id);
    return Done.done();
  }

  // A recurring timer: due at once, then every second.
  @Post("/conformance/recur/{id}")
  public Done recur(String id) {
    timers()
        .scheduleRecurring("recur-" + id, Duration.ZERO, Duration.ofSeconds(1), Reminder::tick, id);
    return Done.done();
  }

  // The same timer set again, with a delay a replacement would be first due after.
  @Post("/conformance/recur/{id}/again")
  public Done recurAgain(String id) {
    timers()
        .scheduleRecurring(
            "recur-" + id, Duration.ofSeconds(60), Duration.ofSeconds(1), Reminder::tick, id);
    return Done.done();
  }

  @Post("/conformance/recur/{id}/cancel")
  public Done recurCancel(String id) {
    timers().cancel("recur-" + id);
    return Done.done();
  }

  @Post("/conformance/recur-refused/{id}")
  public Done recurRefused(String id) {
    try {
      timers().scheduleRecurring("recur-" + id, Duration.ZERO, Duration.ZERO, Reminder::tick, id);
    } catch (IllegalArgumentException e) {
      throw new ProblemException(Problem.badRequest(e.getMessage()));
    }
    return Done.done();
  }

  @Get("/conformance/{id}/count")
  public int count(String id) {
    return entity(id).call(ConformanceEntity::count).invoke();
  }

  // A handler that answers nothing: the call is never awaited, and the route says so with 204.
  @Post("/conformance/{id}/no-reply")
  public Done noReply(String id) {
    entity(id).call(ConformanceEntity::noReply).invokeAsync();
    return Done.done();
  }

  // The Scala reference forwards any handler by its wire name; Java names each one the suite asks.
  @Post("/conformance/{id}/record")
  public String record(String id, @Body String input) {
    return entity(id).call(ConformanceEntity::record).invoke(input);
  }

  @Post("/conformance/{id}/record-many")
  public String recordMany(String id, @Body String n) {
    return entity(id).call(ConformanceEntity::recordMany).invoke(Integer.parseInt(n.trim()));
  }

  @Post("/conformance/{id}/refuse")
  public String refuse(String id) {
    return entity(id).call(ConformanceEntity::refuse).invoke();
  }

  @Post("/conformance/{id}/delete")
  public String delete(String id) {
    return entity(id).call(ConformanceEntity::delete).invoke();
  }

  @Post("/conformance/{id}/expire")
  public String expire(String id, @Body String millis) {
    return entity(id).call(ConformanceEntity::expire).invoke(Long.parseLong(millis.trim()));
  }

  // A handler that throws is answered by nobody: the runtime stops the entity and the call runs
  // out of time. The Scala reference's forwarder awaits its call for ten seconds and answers an
  // unanswered one as an unhandled failure, a 500; this route answers the same way.
  @Post("/conformance/{id}/misbehave")
  public String misbehave(String id) {
    try {
      return entity(id)
          .call(ConformanceEntity::misbehave)
          .withTimeout(Duration.ofSeconds(10))
          .invoke();
    } catch (CommandException e) {
      if (e.problem().status() != 504) throw e;
      throw new IllegalStateException("misbehave was not answered", e);
    }
  }
}
