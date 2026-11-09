package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Routes that read query parameters and headers from the request. */
public class SearchRoutes extends HttpEndpoint {

  public record SearchResult(String term, int limit, List<String> tags, boolean verbose) {}

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  // docs:start query
  /** Required, optional-with-default, repeated, and flag parameters. */
  @Get("/search")
  public SearchResult search() {
    var request = request();
    return new SearchResult(
        request.requiredQuery("q"),
        request.query("limit").map(Integer::parseInt).orElse(20),
        request.queryAll("tag"),
        request.flag("verbose"));
  }
  // docs:end query

  // docs:start header
  /** Headers. */
  @Get("/search/trace")
  public String trace() {
    return request().header("X-Trace-Id").orElse("none");
  }
  // docs:end header

  // docs:start sse-query
  @Sse("/search/stream")
  public Stream<SseEvent> stream() {
    // Read while the handler runs: the stream's elements are pulled later, on another thread.
    var term = request().requiredQuery("q");
    int count = request().query("count").map(Integer::parseInt).orElse(2);
    return IntStream.rangeClosed(1, count).mapToObj(n -> SseEvent.text(term + "-" + n));
  }
  // docs:end sse-query
}
