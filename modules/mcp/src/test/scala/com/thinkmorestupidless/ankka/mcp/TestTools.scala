package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.http.{QueryParams, RequestContext, SimpleRequestContext}

import scala.collection.mutable

/** Builds tools outside an endpoint, as an endpoint's `tool` does, for the pure suites. */
object TestTools:

  final class Declaring:
    private val held = mutable.ListBuffer.empty[McpTool]
    val bindings     = Reference.Bindings()
    def tool(name: String): ToolBuilder =
      ToolBuilder(name, "", None, t => { held += t; t }, bindings)
    def tools: Vector[McpTool] = held.toVector

  def declaring(body: Declaring => Any): Vector[McpTool] =
    val d = Declaring()
    body(d)
    d.tools

  val context: RequestContext =
    SimpleRequestContext(
      "POST",
      "/mcp",
      QueryParams.empty,
      Vector("Host" -> "localhost:9000"),
      None
    )

  def post(json: String, headers: (String, String)*): Protocol.Request =
    Protocol.Request(
      "POST",
      ("Host" -> "localhost:9000") +: headers.toVector,
      json.getBytes("UTF-8")
    )

  def json(text: String): Json =
    Json.parse(text).fold(e => throw IllegalArgumentException(e), identity)

  /** A clock a test moves: "two years later" without waiting two years. */
  final class MovableClock(start: java.time.Instant) extends java.time.Clock:
    @volatile private var now                                      = start
    def advance(by: java.time.Duration): Unit                      = now = now.plus(by)
    def instant(): java.time.Instant                               = now
    def getZone: java.time.ZoneId                                  = java.time.ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): java.time.Clock = this
