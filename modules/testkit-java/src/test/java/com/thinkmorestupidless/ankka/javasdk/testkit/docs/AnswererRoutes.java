package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.Notification;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CartAnswerer;
import java.util.concurrent.Flow;

/** Watches an answerer instance over server-sent events. */
public class AnswererRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  // docs:start notifications
  /**
   * What an answerer instance does, as it happens, as server-sent events. Each event's data is the
   * notification's JSON, as a JSON string, as every event's data is, so a reader parses the field
   * and then the notification. Watching keeps the instance in memory while the stream is open.
   */
  @Sse("/answerer/{instanceId}/notifications")
  public Flow.Publisher<SseEvent> notifications(String instanceId) {
    var notifications =
        AgentClients.forAutonomousAgent(componentClient(), CartAnswerer.class, instanceId)
            .notifications();
    return subscriber ->
        notifications.subscribe(
            new Flow.Subscriber<Notification>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
              }

              @Override
              public void onNext(Notification notification) {
                subscriber.onNext(SseEvent.text(notification.json()));
              }

              @Override
              public void onError(Throwable error) {
                subscriber.onError(error);
              }

              @Override
              public void onComplete() {
                subscriber.onComplete();
              }
            });
  }
  // docs:end notifications
}
