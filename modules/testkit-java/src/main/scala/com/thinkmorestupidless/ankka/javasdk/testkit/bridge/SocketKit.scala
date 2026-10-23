package com.thinkmorestupidless.ankka.javasdk.testkit.bridge

import com.thinkmorestupidless.ankka.javasdk.testkit.TestSocket
import com.thinkmorestupidless.ankka.testkit as stk

import java.time.Duration
import java.util.Optional
import scala.concurrent.duration.FiniteDuration
import scala.jdk.OptionConverters.*

/** The Java test socket over the Scala one. */
final class SocketKit private[bridge] (scala: stk.TestSocket):
  def subprotocol(): Optional[String] = scala.subprotocol.toJava
  def send(text: String): Unit        = scala.send(text)
  def receive(within: Duration): Optional[String] =
    scala.receive(SocketKit.finite(within)).toJava
  def close(): Unit = scala.close()
  def closed(within: Duration): TestSocket.Closed =
    val closed = scala.closed(SocketKit.finite(within))
    TestSocket.Closed(closed.code, closed.reason)

private object SocketKit:
  def finite(d: Duration): FiniteDuration =
    FiniteDuration(d.toNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
