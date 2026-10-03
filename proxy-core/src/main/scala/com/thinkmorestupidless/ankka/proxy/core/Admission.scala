package com.thinkmorestupidless.ankka.proxy.core

/**
 * Who sent a request to the proxy, as read from the connection and never from the request.
 *
 * In a cluster the transport reads it from the peer's certificate; on a developer's machine every
 * sender is `Local`.
 */
enum Sender:
  /**
   * The internet: the gateway, or another web-hosted service passing a request on under one of its
   * mounts. `stated` is the authority that mounting proxy stated the browser used, and is empty for
   * the gateway, which states nothing the proxy believes.
   */
  case Internet(stated: Option[String])

  /** A service of the platform, presenting its own certificate. */
  case Service(project: String, name: String)

  /** The developer's machine, where there is no TLS and no one else. */
  case Local

object Sender:

  /** The sender as the process is told it: `internet`, `service <project>/<service>` or `local`. */
  def describe(sender: Sender): String = sender match
    case Sender.Internet(_)            => "internet"
    case Sender.Service(project, name) => s"service $project/$name"
    case Sender.Local                  => "local"

/** Who a web-hosted service's proxy lets through to the process. */
object Admission:

  /**
   * The internet and the local machine always; the service itself always, as a service's own
   * instances are admitted everywhere; any other service only when the descriptor named it, by name
   * or as every service of the proxy's own project.
   */
  def admits(settings: ProxySettings, sender: Sender): Boolean = sender match
    case Sender.Internet(_) | Sender.Local => true
    case Sender.Service(project, name) =>
      (project == settings.project && name == settings.service) ||
      settings.callers.exists {
        case Admitted.Service(p, n) => p == project && n == name
        case Admitted.AnyInProject  => project == settings.project
      }
