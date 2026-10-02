package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors
import io.grpc.{Metadata, ServerCall, Status}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{Attributes, Materializer}

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

/**
 * The requests of a method that takes a stream, read on the handler's own thread.
 *
 * Each `hasNext` asks the caller for one more part and blocks until it arrives or the caller ends
 * the stream; nothing is asked for before the handler asks, so a caller is held to sending no
 * faster than the handler reads. A handler that answers before reading everything simply stops
 * asking, and the rest is never read.
 *
 * `asSource` gives the same parts as a stream, with the same demand. Use one of the two, not both.
 */
trait Requests[Req] extends Iterator[Req]:
  def asSource: Source[Req, NotUsed]

/**
 * Thrown by `Requests`, and failing its `asSource`, when the call ended before the caller finished
 * sending: the caller cancelled or went away, or sent a part that could not be read. The call has
 * already ended; a handler may catch it to see what it read up to then.
 */
final class CallCancelled(message: String) extends RuntimeException(message):
  override def fillInStackTrace(): Throwable = this

/**
 * One call's outgoing half, shared by whatever writes to it: grpc-java's `ServerCall` is not
 * thread-safe, and a handler streaming answers can be ended by a part it failed to read.
 */
private[grpc] final class Outgoing(call: ServerCall[Array[Byte], Any]):
  private var headersSent = false
  private var closed      = false

  def send(message: Any): Unit = synchronized {
    if !closed then
      if !headersSent then
        call.sendHeaders(Metadata())
        headersSent = true
      call.sendMessage(message)
  }

  def close(status: Status, trailers: Metadata): Unit = synchronized {
    if !closed then
      closed = true
      call.close(status, trailers)
  }

  def isClosed: Boolean = synchronized(closed)

  def isReady: Boolean = call.isReady

/** Waits for the call to be ready for another message, which grpc-java reports with `onReady`. */
private[grpc] final class Readiness:
  private val lock = Object()

  def signal(): Unit = lock.synchronized(lock.notifyAll())

  /**
   * Returns when `ready` holds or `stop` does. The timed wait is a backstop for a signal that came
   * between the check and the wait; the signal is what wakes it in practice.
   */
  def await(ready: => Boolean, stop: => Boolean): Unit =
    while !ready && !stop do lock.synchronized(lock.wait(50))

private[grpc] object Streams:

  private enum Signal:
    case Part(bytes: Array[Byte])
    case End
    case Cancelled(reason: String)

  /**
   * The requests of one call, fed by its listener and read by its handler.
   *
   * At most one part is ever undelivered: the next is asked for only when the handler asks for it.
   */
  final class Inbound(
      call: ServerCall[Array[Byte], Any],
      out: Outgoing,
      parse: Array[Byte] => Either[Status, Any]
  ) extends Requests[Any]:
    private val arrived                   = LinkedBlockingQueue[Signal]()
    private var lookahead: Option[Signal] = None
    private var asked                     = false
    @volatile private var cancelled       = false

    // Listener side.
    def onMessage(bytes: Array[Byte]): Unit = arrived.put(Signal.Part(bytes))
    def onHalfClose(): Unit                 = arrived.put(Signal.End)
    def onCancel(reason: String): Unit =
      cancelled = true
      arrived.put(Signal.Cancelled(reason))

    def isCancelled: Boolean = cancelled

    // Handler side.
    private def peek(): Signal =
      lookahead.getOrElse {
        if !asked then
          call.request(1)
          asked = true
        val signal = arrived.take()
        lookahead = Some(signal)
        signal
      }

    def hasNext: Boolean = peek() match
      case Signal.Part(_)           => true
      case Signal.End               => false
      case Signal.Cancelled(reason) => throw CallCancelled(reason)

    def next(): Any = peek() match
      case Signal.Part(bytes) =>
        lookahead = None
        asked = false
        parse(bytes) match
          case Right(value) => value
          case Left(status) =>
            cancelled = true
            out.close(status, Metadata())
            throw CallCancelled(status.getDescription)
      case Signal.End => throw java.util.NoSuchElementException("the caller has ended the stream")
      case Signal.Cancelled(reason) => throw CallCancelled(reason)

    def asSource: Source[Any, NotUsed] =
      Source.unfoldAsync(())(_ =>
        Future(if hasNext then Some(() -> next()) else None)(using AnkkaExecutors.virtual)
      )

  /**
   * Sends `source`'s parts as the caller can take them, then ends the call: `OK` when the stream
   * completes, the failure's status when it fails, nothing more when the call already ended.
   *
   * Runs on the handler's virtual thread, which it blocks. A part is taken from the stream only
   * while the call can send it, so a caller that reads slowly makes the stream produce slowly.
   */
  def drain(
      source: Source[Any, ?],
      out: Outgoing,
      readiness: Readiness,
      cancelled: => Boolean,
      materializer: Materializer,
      failed: Throwable => (Status, Metadata)
  ): Unit =
    // A failure travels as the stream's last element rather than as the stream failing: a queue
    // sink fails its next pull the moment the stream fails, overtaking parts it had already taken,
    // and a stream that ends in a refusal must deliver every part before it.
    val parts = source
      .map(Right(_))
      .recover { case NonFatal(failure) => Left(failure) }
      .runWith(Sink.queue[Either[Throwable, Any]]().withAttributes(Attributes.inputBuffer(1, 1)))(
        using materializer
      )
    try
      var done = false
      while !done do
        readiness.await(out.isReady, cancelled || out.isClosed)
        if cancelled || out.isClosed then
          parts.cancel()
          done = true
        else
          Await.result(parts.pull(), Duration.Inf) match
            case Some(Right(part)) => out.send(part)
            case Some(Left(failure)) =>
              val (status, trailers) = failed(failure)
              out.close(status, trailers)
              done = true
            case None =>
              out.close(Status.OK, Metadata())
              done = true
    catch
      case NonFatal(failure) =>
        parts.cancel()
        val (status, trailers) = failed(failure)
        out.close(status, trailers)
