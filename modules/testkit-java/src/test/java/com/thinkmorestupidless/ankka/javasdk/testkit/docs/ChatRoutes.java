package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.util.concurrent.Flow;

/** An agent's stream served as server-sent events. */
public class ChatRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  // docs:start sse
  /** Each token the agent writes, as one event. */
  @Sse("/chat/{session}")
  public Flow.Publisher<SseEvent> chat(String session) {
    var tokens =
        AgentClients.forAgent(componentClient(), session)
            .stream(WeatherAgent::chat, "What is the weather?");
    return asEvents(tokens);
  }

  // The SDK has no mapper over a Flow.Publisher; this one passes each token on, at the
  // subscriber's pace.
  private static Flow.Publisher<SseEvent> asEvents(Flow.Publisher<String> tokens) {
    return subscriber ->
        tokens.subscribe(
            new Flow.Subscriber<String>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
              }

              @Override
              public void onNext(String token) {
                subscriber.onNext(SseEvent.text(token));
              }

              @Override
              public void onError(Throwable failure) {
                subscriber.onError(failure);
              }

              @Override
              public void onComplete() {
                subscriber.onComplete();
              }
            });
  }
  // docs:end sse
}
