package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.Socket;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.annotations.StreamBody;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import com.thinkmorestupidless.ankka.javasdk.http.StreamedBody;
import com.thinkmorestupidless.ankka.javasdk.http.WebSocket;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Routes answered with a stream of events, a socket, and a body read as it arrives. */
public class Streams extends HttpEndpoint {

  /** Counted down when an upload's handler reads its first byte. */
  public static volatile CountDownLatch firstRead = new CountDownLatch(1);

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Sse("/streams/count/{n}")
  public Stream<SseEvent> count(int n) {
    return IntStream.rangeClosed(1, n).mapToObj(i -> SseEvent.text("tick " + i));
  }

  /** Named events published from another thread once someone is reading. */
  @Sse("/streams/published")
  public Flow.Publisher<SseEvent> published() {
    var publisher = new SubmissionPublisher<SseEvent>();
    Thread.ofVirtual()
        .start(
            () -> {
              while (publisher.getNumberOfSubscribers() == 0) Thread.onSpinWait();
              for (int i = 1; i <= 3; i++)
                publisher.submit(SseEvent.named("line", "{\"n\":" + i + "}"));
              publisher.close();
            });
    return publisher;
  }

  @Socket("/streams/echo")
  public void echo(WebSocket socket) {
    Optional<String> frame;
    while ((frame = socket.receive()).isPresent()) {
      if (frame.get().equals("bye")) return;
      socket.send("echo: " + frame.get());
    }
  }

  /** Keeps the body in the bucket as it arrives. */
  @StreamBody("/streams/upload/{name}")
  public long upload(String name, StreamedBody body) {
    var counted =
        new FilterInputStream(body.body()) {
          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) firstRead.countDown();
            return n;
          }
        };
    long length = body.length().orElseThrow();
    storage().defaultBucket().put(name, counted, length, body.contentType());
    return length;
  }
}
