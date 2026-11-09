package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.http.Caller;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Requests a test makes as a named caller. */
public final class CallerChecks {

  private CallerChecks() {}

  // docs:start test-as-caller
  /** The status of a GET made as {@code caller}: the kit's header names the caller. */
  public static int statusAs(AnkkaTestKit kit, String path, Caller caller) throws Exception {
    var asCaller = kit.asCaller(caller);
    var request =
        HttpRequest.newBuilder(kit.http().resolve(path))
            .header(asCaller.getKey(), asCaller.getValue())
            .build();
    return kit.httpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  // statusAs(kit, "/callers/only-orders", new Caller.Service("local", "orders")) is 200;
  // statusAs(kit, "/callers/only-orders", new Caller.Service("local", "payments")) is 403.
  // docs:end test-as-caller
}
