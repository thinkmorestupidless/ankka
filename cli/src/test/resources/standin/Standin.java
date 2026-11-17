import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for a service, for LocalUpSuite (feature 066). JDK only, one file, run in its image or
 * from source on the host (`java Standin.java`).
 *
 * <ul>
 *   <li>{@code GET /observability/service} at {@code ANKKA_OBSERVABILITY_ADDRESS} ({@code host:port},
 *       loopback and a free port when unset): the document a runtime serves, with its HTTP address.
 *   <li>{@code GET /env/<name>} at {@code ANKKA_HTTP_PORT}: the variable's value, or 404.
 *   <li>{@code PUT /records/<id>} and {@code GET /records/<id>}: a row of {@code standin_records} in
 *       the service's own database, through {@code psql}; 501 with {@code ANKKA_DATABASE=none}.
 *   <li>{@code GET /call/<service>/<path>}: a GET to {@code <path>} on the other service, found by the
 *       {@code ankka.local-services.<service>} property, else by its entry in the directory
 *       {@code STANDIN_ANNOUNCE} names, as a runtime finds it; answered with what came back.
 * </ul>
 *
 * {@code STANDIN_FAIL=start} exits 1 before serving. {@code STANDIN_ANNOUNCE=<dir>} writes an entry
 * for the local console there, with no owner, as a service run from its source does.
 */
public final class Standin {

  private static final Map<String, String> env = System.getenv();
  private static final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public static void main(String[] args) throws Exception {
    String name = env.getOrDefault("ANKKA_SERVICE_NAME", "standin");
    if ("start".equals(env.get("STANDIN_FAIL"))) {
      System.out.println("standin " + name + " was told to fail at start");
      System.exit(1);
    }
    int httpPort = Integer.parseInt(env.getOrDefault("ANKKA_HTTP_PORT", "0"));
    HttpServer http = HttpServer.create(new InetSocketAddress("0.0.0.0", httpPort), 0);
    http.createContext("/", Standin::serve);
    http.start();
    int boundHttp = http.getAddress().getPort();

    String named = env.getOrDefault("ANKKA_OBSERVABILITY_ADDRESS", "");
    InetSocketAddress at =
        named.isEmpty()
            ? new InetSocketAddress("127.0.0.1", 0)
            : new InetSocketAddress(
                named.substring(0, named.lastIndexOf(':')),
                Integer.parseInt(named.substring(named.lastIndexOf(':') + 1)));
    HttpServer observability = HttpServer.create(at, 0);
    String document =
        "{\"name\":" + quote(name) + ",\"runtime\":\"standin\",\"instances\":[{\"id\":\"1\","
            + "\"startedAt\":" + quote(Instant.now().toString()) + ","
            + "\"http\":{\"address\":" + quote("http://127.0.0.1:" + boundHttp) + "}}],"
            + "\"components\":[{\"kind\":\"EventSourcedEntity\",\"id\":\"standin-records\","
            + "\"sharded\":true,\"queries\":[]}],"
            + "\"routes\":[{\"method\":\"GET\",\"path\":\"/env/{name}\",\"streaming\":false,"
            + "\"endpoint\":\"standin\"}],\"topicSources\":[]}";
    observability.createContext(
        "/observability/service", exchange -> respond(exchange, 200, document));
    observability.start();

    String announce = env.get("STANDIN_ANNOUNCE");
    if (announce != null && !announce.isEmpty()) {
      long pid = ProcessHandle.current().pid();
      Path entry = Path.of(announce).resolve(pid + ".json");
      Files.createDirectories(entry.getParent());
      Files.writeString(
          entry,
          "{\"name\":" + quote(name) + ",\"instanceId\":" + quote(Long.toString(pid))
              + ",\"pid\":" + pid + ",\"observabilityAddress\":"
              + quote("http://127.0.0.1:" + observability.getAddress().getPort())
              + ",\"startedAt\":" + quote(Instant.now().toString()) + "}");
      Runtime.getRuntime().addShutdownHook(new Thread(() -> entry.toFile().delete()));
    }
    System.out.println("standin " + name + " started");
    System.out.flush();
  }

  private static void serve(HttpExchange exchange) throws IOException {
    try {
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      if (method.equals("GET") && path.startsWith("/env/")) {
        String value = env.get(path.substring("/env/".length()));
        if (value == null) respond(exchange, 404, "unset");
        else respond(exchange, 200, value);
      } else if (path.startsWith("/records/")) {
        record(exchange, method, path.substring("/records/".length()));
      } else if (method.equals("GET") && path.startsWith("/call/")) {
        call(exchange, path.substring("/call/".length()));
      } else {
        respond(exchange, 404, "no route " + method + " " + path);
      }
    } catch (Exception e) {
      respond(exchange, 500, String.valueOf(e.getMessage()));
    }
  }

  private static void record(HttpExchange exchange, String method, String id) throws Exception {
    if ("none".equals(env.get("ANKKA_DATABASE"))) {
      respond(exchange, 501, "no database");
      return;
    }
    String key = id.replace("'", "''");
    psql("CREATE TABLE IF NOT EXISTS standin_records (id text PRIMARY KEY, body text NOT NULL)");
    if (method.equals("PUT")) {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      psql(
          "INSERT INTO standin_records VALUES ('" + key + "', '" + body.replace("'", "''")
              + "') ON CONFLICT (id) DO UPDATE SET body = EXCLUDED.body");
      respond(exchange, 200, "recorded");
    } else {
      String found = psql("SELECT body FROM standin_records WHERE id = '" + key + "'").trim();
      if (found.isEmpty()) respond(exchange, 404, "no record " + id);
      else respond(exchange, 200, found);
    }
  }

  private static String psql(String sql) throws Exception {
    List<String> command = new ArrayList<>(List.of(
        "psql", "-v", "ON_ERROR_STOP=1", "-tA",
        "-h", env.getOrDefault("ANKKA_DB_HOST", "localhost"),
        "-p", env.getOrDefault("ANKKA_DB_PORT", "5432"),
        "-U", env.getOrDefault("ANKKA_DB_USER", "ankka"),
        "-d", env.getOrDefault("ANKKA_DB_NAME", "postgres"),
        "-c", sql));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("PGPASSWORD", env.getOrDefault("ANKKA_DB_PASSWORD", "ankka"));
    Process process = builder.start();
    String out;
    try (InputStream in = process.getInputStream()) {
      out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (process.waitFor() != 0) throw new IOException("psql: " + out.trim());
    return out;
  }

  private static void call(HttpExchange exchange, String rest) throws Exception {
    int slash = rest.indexOf('/');
    String service = slash < 0 ? rest : rest.substring(0, slash);
    String path = slash < 0 ? "/" : rest.substring(slash);
    String base = System.getProperty("ankka.local-services." + service);
    if (base == null) base = announced(service);
    if (base == null) {
      respond(exchange, 503, "cannot find " + service);
      return;
    }
    HttpResponse<String> answer = client.send(
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).build(),
        HttpResponse.BodyHandlers.ofString());
    respond(exchange, answer.statusCode(), answer.body());
  }

  /** Where a service announced in STANDIN_ANNOUNCE's directory serves HTTP, as the runtime asks it. */
  private static String announced(String service) {
    String dir = env.get("STANDIN_ANNOUNCE");
    if (dir == null || dir.isEmpty()) return null;
    Pattern nameField = Pattern.compile("\"name\":\"([^\"]*)\"");
    Pattern addressField = Pattern.compile("\"observabilityAddress\":\"([^\"]*)\"");
    Pattern httpField = Pattern.compile("\"http\":\\{\"address\":\"([^\"]*)\"");
    java.io.File[] files = new java.io.File(dir).listFiles((d, n) -> n.endsWith(".json"));
    if (files == null) return null;
    for (java.io.File file : files) {
      try {
        String json = Files.readString(file.toPath());
        Matcher n = nameField.matcher(json);
        Matcher a = addressField.matcher(json);
        if (!n.find() || !n.group(1).equals(service) || !a.find()) continue;
        String document = client.send(
            HttpRequest.newBuilder(URI.create(a.group(1) + "/observability/service"))
                .timeout(Duration.ofSeconds(2)).build(),
            HttpResponse.BodyHandlers.ofString()).body();
        Matcher h = httpField.matcher(document);
        if (h.find()) return h.group(1);
      } catch (Exception ignored) {
        // Not answering: as a runtime, look at the next.
      }
    }
    return null;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
