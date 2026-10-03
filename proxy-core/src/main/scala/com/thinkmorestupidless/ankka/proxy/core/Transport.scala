package com.thinkmorestupidless.ankka.proxy.core

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetAddress, InetSocketAddress}

/**
 * The half of the proxy that depends on where it runs: how the public listener is made, and who a
 * connection's sender is. In a cluster both come from mutual TLS; on a developer's machine the
 * listener is plain and every sender is the local machine.
 */
trait Transport:

  /** A server bound to `port` and not yet started; the engine gives it its handler and executor. */
  def listener(port: Int): HttpServer

  /** Who sent an exchange, or why nobody the proxy knows did. */
  def senderOf(exchange: HttpExchange): Either[String, Sender]

object Transport:

  /** Plain HTTP on `bind`, every sender `Local`: what `ankka local web` runs. */
  def plain(bind: InetAddress): Transport = new Transport:
    def listener(port: Int): HttpServer = HttpServer.create(new InetSocketAddress(bind, port), 0)
    def senderOf(exchange: HttpExchange): Either[String, Sender] = Right(Sender.Local)
