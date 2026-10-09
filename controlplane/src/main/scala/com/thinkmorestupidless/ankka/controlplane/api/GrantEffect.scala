package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.domain.Grant

/**
 * Whether a grant opens what it names, or why not (feature 040): the `EFFECT` a project's listing
 * shows. A grant that is not accepted says its state. An accepted one on a route or a method is
 * read against the service it names, in this order: a web-hosted service has no route a grant can
 * open; one with no ready instance shows no route; one whose instances read no grants waits for a
 * rollout; a route the running service does not have is not seen, and one whose ACL does not name
 * granted callers is not grantable. A machine's topic grant waits for an installation that exposes
 * its broker. Anything else is in effect.
 */
object GrantEffect:

  val InEffect          = "in effect"
  val RouteNotSeen      = "route not seen"
  val RouteNotGrantable = "route not grantable"
  val RolloutNeeded     = "rollout needed"
  val BrokerNotExposed  = "broker not exposed"

  def of(
      grant: Grant,
      status: Option[ServiceStatus],
      documents: Vector[InstanceTopologyDocument],
      brokerExposed: Boolean
  ): String =
    if grant.state != GrantState.Accepted then grant.state.word
    else
      grant.target.kind match
        case GrantTarget.Route | GrantTarget.Method => onService(grant.target, status, documents)
        case GrantTarget.Topic =>
          grant.grantee match
            case _: Grantee.Machine if !brokerExposed => BrokerNotExposed
            case _                                    => InEffect
        case _ => InEffect

  private def onService(
      target: GrantTarget,
      status: Option[ServiceStatus],
      documents: Vector[InstanceTopologyDocument]
  ): String =
    status match
      case None                                    => RouteNotSeen
      case Some(s) if s.hosting == ServiceSpec.Web => RouteNotGrantable
      case Some(s) if s.readyInstances < 1         => RouteNotSeen
      case Some(s) if s.grants.isEmpty             => RolloutNeeded
      case Some(_) =>
        val handlers = documents
          .flatMap(_.nodes)
          .filter(_.kind == "Endpoint")
          .flatMap(_.handlers)
          .filter(h => opens(target, h.name))
        if handlers.isEmpty then RouteNotSeen
        else if handlers.exists(_.grantable.contains(true)) then InEffect
        else RouteNotGrantable

  /** Whether a route handler, named `METHOD /path`, is what `target` names. */
  def opens(target: GrantTarget, handler: String): Boolean =
    val (method, path) = handler.span(_ != ' ')
    target.kind match
      case GrantTarget.Route =>
        target.method.contains(method) && target.path.exists(p => shape(p) == shape(path.trim))
      case GrantTarget.Method =>
        target.method.exists(m => path.trim.endsWith(s".$m") || path.trim == s"/$m")
      case _ => false

  /** A template with its parameters' names left out: what makes two templates one route. */
  private def shape(template: String): String = template.replaceAll("\\{[^}]*\\}", "{}")
