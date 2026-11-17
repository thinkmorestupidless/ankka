package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.LocalState

/** Whether a port is free, asked as Docker will bind it. */
class LocalStateSuite extends munit.FunSuite:

  test(
    "a port a listener holds on loopback is in use, and one a closed connection lingers on is not"
  ) {
    val listener = java.net.ServerSocket()
    listener.bind(java.net.InetSocketAddress("127.0.0.1", 0))
    val port = listener.getLocalPort
    assert(!LocalState.isFree(port), "a listener on loopback holds its port")
    // The server's side closes first, so the port's end of the connection waits in TIME_WAIT.
    val client   = java.net.Socket("127.0.0.1", port)
    val accepted = listener.accept()
    accepted.close()
    listener.close()
    client.close()
    assert(
      LocalState.isFree(port),
      "a connection in TIME_WAIT holds nothing Docker cannot bind over"
    )
  }
