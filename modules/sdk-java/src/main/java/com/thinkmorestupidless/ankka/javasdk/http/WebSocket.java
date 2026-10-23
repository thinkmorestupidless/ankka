package com.thinkmorestupidless.ankka.javasdk.http;

import java.util.Optional;

/**
 * The open socket an {@code @Socket} route is handed. The handler runs on one virtual thread for the
 * socket's life, so it is ordinary blocking code: the socket closes when the handler returns.
 */
public interface WebSocket {

  /** The next text frame, waiting for it; empty once the client has closed the socket. */
  Optional<String> receive();

  /** Sends a text frame; throws once the socket is closed. */
  void send(String text);
}
