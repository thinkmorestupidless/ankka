package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.auth.Authorization
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}

import scala.util.control.NonFatal

/**
 * Erasure requests across projects (feature 042, `GET /erasures?correlation=`): the requests of
 * every project the caller may read that carry one correlation id, so an auditor reading one
 * project finds the request the domain asked for in another. A project the caller may not read is
 * left out without a word; the listing says nothing about it.
 */
final class ErasuresEndpoint(clients: EndpointClients, val acl: Acl, clock: java.time.Clock)
    extends HttpEndpoint("/erasures"):

  private val authz     = Authorization(clients, clock)
  private lazy val rows = clients.viewClient.forView(ErasureRequestsView)

  get("/") { () =>
    val correlation = request.query
      .optional[String]("correlation")
      .filter(_.nonEmpty)
      .getOrElse(
        throw CommandError("a listing across projects names a correlation id", ErrorCode.BadRequest)
      )
    val limit = request.query.optional[Int]("limit").getOrElse(100)
    if limit < 1 || limit > 1000 then
      throw CommandError("limit must be between 1 and 1000", ErrorCode.BadRequest)
    val readable                          = scala.collection.mutable.Map.empty[String, Boolean]
    def mayRead(project: String): Boolean =
      readable.getOrElseUpdate(
        project,
        try
          authz.project(principal, project, write = false): Unit
          true
        catch case NonFatal(_) => false
      )
    rows
      .ask(ErasureRequestsView.byCorrelation, "correlation" -> correlation)
      .iterator
      .filter(r => mayRead(r.project))
      .take(limit)
      .map(r => ErasureRoutes.summary(r).copy(project = Some(r.project)))
      .toVector
  }
