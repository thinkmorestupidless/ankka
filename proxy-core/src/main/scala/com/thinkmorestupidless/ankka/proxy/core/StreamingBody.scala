package com.thinkmorestupidless.ankka.proxy.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.Flow

/**
 * A request body passed on as it arrives: each read becomes one part, sent as soon as the client
 * asks for one.
 *
 * The JDK's own `BodyPublishers.ofInputStream` reads on the client's threads, and a part it has
 * read is not written until a later read returns: a body that trickles in reaches the other side
 * only once the sender has sent more, or all of it. Reading on a virtual thread of the publisher's
 * own leaves the client's threads free to write each part out.
 */
private[core] final class StreamingBody(open: () => InputStream) extends Flow.Publisher[ByteBuffer]:

  def subscribe(subscriber: Flow.Subscriber[? >: ByteBuffer]): Unit =
    val lock                = new Object
    var demand              = 0L
    @volatile var cancelled = false
    subscriber.onSubscribe(new Flow.Subscription:
      def request(n: Long): Unit = lock.synchronized {
        demand = if Long.MaxValue - demand < n then Long.MaxValue else demand + n
        lock.notifyAll()
      }
      def cancel(): Unit =
        cancelled = true
        lock.synchronized(lock.notifyAll()))
    Thread
      .ofVirtual()
      .name("ankka-proxy-body")
      .start(() =>
        try
          val in     = open()
          val buffer = new Array[Byte](StreamingBody.PartSize)
          var read   = in.read(buffer)
          while read >= 0 && !cancelled do
            if read > 0 then
              lock.synchronized {
                while demand == 0 && !cancelled do lock.wait()
                demand -= 1
              }
              if !cancelled then subscriber.onNext(ByteBuffer.wrap(Arrays.copyOf(buffer, read)))
            read = in.read(buffer)
          if !cancelled then subscriber.onComplete()
        catch case error: Throwable => if !cancelled then subscriber.onError(error)
      ): Unit

private[core] object StreamingBody:
  val PartSize: Int = 16 * 1024
