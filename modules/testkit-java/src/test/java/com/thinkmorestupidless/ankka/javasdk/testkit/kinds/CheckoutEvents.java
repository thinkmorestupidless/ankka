package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.time.Duration;
import java.util.concurrent.Flow;

/** A checkout's end, waited for over server-sent events. */
public class CheckoutEvents extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Sse("/checkouts/{id}/end")
  public Flow.Publisher<SseEvent> end(String id) {
    return componentClient().forWorkflow(id).awaitEndEvents(Checkout.class, Duration.ofSeconds(30));
  }
}
