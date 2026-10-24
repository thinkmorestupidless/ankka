package com.thinkmorestupidless.ankka.javasdk.http;

/** Why the platform closed a socket, as the close code and reason a client reads. */
public enum CloseReason {
  /** The handler returned. */
  FINISHED(1000, "finished"),
  /** The instance is stopping; open the socket again and another instance answers. */
  GOING_AWAY(1001, "going away"),
  /** A frame that is not text arrived. */
  NOT_TEXT(1003, "not text"),
  /** More frames were waiting for the handler than a socket holds. */
  UNREAD(1008, "unread"),
  /** A frame was larger than a frame may be. */
  TOO_LARGE(1009, "too large"),
  /** The grant that admitted the socket ended; opening it again is refused. */
  REVOKED(1008, "revoked"),
  /** The handler failed. */
  FAILED(1011, "failed");

  private final int code;
  private final String word;

  CloseReason(int code, String word) {
    this.code = code;
    this.word = word;
  }

  /** The close code. */
  public int code() {
    return code;
  }

  /** The reason sent with it. */
  public String word() {
    return word;
  }
}
