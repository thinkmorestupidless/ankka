package com.thinkmorestupidless.ankka.http

/**
 * A handler's hold on one open socket: the connection a request to a socket route opened.
 *
 * Both calls block, which is what a socket's handler is for: it runs on a virtual thread for as
 * long as the socket is open, so waiting for a frame parks that thread and holds no other. The
 * request that opened the socket — its caller, its principal, its query and headers — is the
 * handler's `request` for all of that time.
 */
trait Socket:

  /**
   * The next frame, waiting for one. `None` once the socket is closed — by the client, by a limit,
   * by the instance stopping — and for ever after.
   */
  def receive(): Option[String]

  /** Sends a frame, waiting while the client is not reading. Throws `SocketClosed` once closed. */
  def send(text: String): Unit

  /**
   * Why the socket closed, once it has: a close reason's word, or "client" for the client's own
   * close. What a relay tells the process behind it.
   */
  private[ankka] def closedBecause: Option[String] = None

/**
 * Thrown by `send` once the socket is closed. A handler that lets it escape has ended the way a
 * handler ends when its client goes: it is not recorded or logged as a failure.
 */
final class SocketClosed(val reason: String)
    extends RuntimeException(s"the socket is closed ($reason)"):
  override def fillInStackTrace(): Throwable = this

/**
 * Why a socket was closed, as its client is told: a word and the close code that carries it.
 *
 * A client's own close is not one of these; the platform answers it with the client's code.
 */
enum CloseReason(val code: Int, val word: String):
  /** The handler returned. */
  case Finished extends CloseReason(1000, "finished")

  /** The instance is stopping; open the socket again and another instance answers. */
  case GoingAway extends CloseReason(1001, "going away")

  /** A frame that is not text arrived. */
  case NotText extends CloseReason(1003, "not text")

  /** More frames were waiting for the handler than a socket holds. */
  case Unread extends CloseReason(1008, "unread")

  /** A frame was larger than a frame may be. */
  case TooLarge extends CloseReason(1009, "too large")

  /** The grant that admitted the socket ended (feature 040); opening it again is refused. */
  case Revoked extends CloseReason(1008, "revoked")

  /** The handler failed, or the process behind it did. */
  case Failed extends CloseReason(1011, "failed")
