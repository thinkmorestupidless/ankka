package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.http.CloseReason;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.SocketKit;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * A socket opened to a running service by {@link AnkkaTestKit#socket(String)}: what a client
 * sends and reads, and how the service closed it.
 */
public final class TestSocket implements AutoCloseable {

  private static final Duration WAIT = Duration.ofSeconds(5);

  private final SocketKit socket;

  TestSocket(SocketKit socket) {
    this.socket = socket;
  }

  /** The subprotocol the service chose, if it chose one. */
  public Optional<String> subprotocol() {
    return socket.subprotocol();
  }

  /** Sends one text frame. */
  public void send(String text) {
    socket.send(text);
  }

  /** The next text frame, waiting up to five seconds; empty when none came. */
  public Optional<String> receive() {
    return receive(WAIT);
  }

  /** The next text frame, waiting up to {@code within}; empty when none came. */
  public Optional<String> receive(Duration within) {
    return socket.receive(within);
  }

  /** Closes the socket from the client's side, normally. */
  @Override
  public void close() {
    socket.close();
  }

  /**
   * How the service closed the socket, waiting up to five seconds for it to; fails when the socket
   * was cut off rather than closed, or did not end.
   */
  public Closed closed() {
    return closed(WAIT);
  }

  /** {@link #closed()}, waiting up to {@code within}. */
  public Closed closed(Duration within) {
    return socket.closed(within);
  }

  /** The close code and reason a client read. */
  public record Closed(int code, String reason) {

    /** Whether this is the close {@code reason} names. */
    public boolean is(CloseReason reason) {
      return code == reason.code() && this.reason.equals(reason.word());
    }
  }

  /** The opening request was answered with something other than an upgrade. */
  public static final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient Map<String, String> headers;

    public Refused(int status, Map<String, String> headers) {
      super("the socket was refused with " + status);
      this.status = status;
      this.headers = Map.copyOf(headers);
    }

    public int status() {
      return status;
    }

    public Map<String, String> headers() {
      return headers;
    }
  }
}
