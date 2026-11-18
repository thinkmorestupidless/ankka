package com.thinkmorestupidless.ankka.http.openapi

/**
 * What a server reports to a test that holds it to its OpenAPI document (feature 067, research
 * R10). A check only listens: nothing it does changes an answer. Each `route` is the operation's
 * name as the document writes it, `GET /players/{playerId}`.
 */
private[ankka] trait DocumentCheck:

  /** An answer, with its body when it was written whole; a streamed body is not read. */
  def answered(route: String, status: Int, contentType: String, body: Option[Array[Byte]]): Unit

  /** The body of a request the route took, and the status it was answered with. */
  def accepted(route: String, body: Array[Byte], status: Int): Unit

  /** A query parameter the route's handler read. */
  def queried(route: String, name: String): Unit

private[ankka] object DocumentCheck:

  /** The handler's reader of query parameters, set only while a handler runs under a check. */
  private val reading = ThreadLocal[String => Unit]()

  /**
   * Runs `body`, a handler, reporting each query parameter it reads. Set inside the handler's own
   * scope, so an ACL's read of the same parameters, made before, is not counted.
   */
  def reporting[A](report: String => Unit)(body: => A): A =
    reading.set(report)
    try body
    finally reading.remove()

  /** Called by `QueryParams` on every read: nothing at all unless a check is listening. */
  private[ankka] def read(name: String): Unit =
    val report = reading.get()
    if report != null then report(name)

  /** Whether a check listens to the handler running on this thread. */
  private[ankka] def listening: Boolean = reading.get() != null
