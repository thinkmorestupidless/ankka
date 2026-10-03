package com.thinkmorestupidless.ankka.proxy.core

import scala.concurrent.duration.*

/** Who a web-hosted service's proxy admits beside the internet, itself and the local machine. */
enum Admitted:
  /** One named service, in whichever project. */
  case Service(project: String, name: String)

  /** Every service of the proxy's own project. */
  case AnyInProject

/**
 * What a proxy is told about the one web-hosted service it stands in front of.
 *
 * In a cluster the operator writes these as the proxy container's environment; on a developer's
 * machine `ankka local web` builds one from a descriptor. The strings the operator writes are read
 * here and nowhere else, and a suite with both on its classpath holds the two to each other.
 *
 * @param mounts
 *   each a path and the service of the proxy's project that answers requests under it, in the order
 *   the descriptor declared them
 * @param publicAuthority
 *   the host, and the port when it is not 443, a browser addresses the service by: what the process
 *   is told a request from the internet was sent to. Absent when the installation has no base
 *   domain
 */
final case class ProxySettings(
    project: String,
    service: String,
    port: Int,
    processPort: Int,
    probePort: Int = ProxySettings.ProbePort,
    callingPort: Int = ProxySettings.CallingPort,
    mounts: Vector[(String, String)] = Vector.empty,
    callers: Vector[Admitted] = Vector.empty,
    publicAuthority: Option[String] = None,
    namespacePrefix: String = "ankka",
    responseTimeout: FiniteDuration = 60.seconds,
    drainTimeout: FiniteDuration = 10.seconds
)

object ProxySettings:

  val ProbePort: Int   = 7627
  val CallingPort: Int = 7630

  /**
   * Every variable the operator sets on the proxy container. `ANKKA_NAMESPACE_PREFIX` is the one
   * every workload gets; the others are the proxy's own.
   */
  object Variables:
    val Project         = "ANKKA_PROXY_PROJECT"
    val Service         = "ANKKA_PROXY_SERVICE"
    val Port            = "ANKKA_PROXY_PORT"
    val ProcessPort     = "ANKKA_PROXY_PROCESS_PORT"
    val Mounts          = "ANKKA_PROXY_MOUNTS"
    val Callers         = "ANKKA_PROXY_CALLERS"
    val PublicAuthority = "ANKKA_PROXY_PUBLIC_AUTHORITY"
    val NamespacePrefix = "ANKKA_NAMESPACE_PREFIX"

  /** `/api/cart=cart,/api/orders=orders`: the form the operator writes mounts in. */
  def encodeMounts(mounts: Seq[(String, String)]): String =
    mounts.map((path, service) => s"$path=$service").mkString(",")

  /** `orders,billing/invoices,*`, as the descriptor wrote them. */
  def encodeCallers(entries: Seq[String]): String = entries.mkString(",")

  private val Label = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

  /** One caller entry, in the proxy's own project's terms. */
  def parseCaller(entry: String, ownProject: String): Option[Admitted] =
    entry.split("/", -1) match
      case Array("*")                         => Some(Admitted.AnyInProject)
      case Array(name) if Label.matches(name) => Some(Admitted.Service(ownProject, name))
      case Array(project, name) if Label.matches(project) && Label.matches(name) =>
        Some(Admitted.Service(project, name))
      case _ => None

  /** The settings in an environment, or every problem with it at once, each naming its variable. */
  def fromEnvironment(env: String => Option[String]): Either[Vector[String], ProxySettings] =
    import Variables.*
    def required(name: String): Either[String, String] =
      env(name).filter(_.nonEmpty).toRight(s"$name is not set")
    def port(name: String): Either[String, Int] =
      required(name).flatMap(text =>
        text.toIntOption.filter(p => p >= 1 && p <= 65535).toRight(s"$name is not a port: '$text'")
      )
    def list(name: String): Vector[String] =
      env(name).toVector.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty)

    val project     = required(Project)
    val service     = required(Service)
    val ownPort     = port(Port)
    val processPort = port(ProcessPort)
    val mounts = list(Mounts).map { entry =>
      entry.split("=", 2) match
        case Array(path, service) if path.startsWith("/") && service.nonEmpty =>
          Right(path -> service)
        case _ => Left(s"$Mounts has an entry that is not <path>=<service>: '$entry'")
    }
    val callers = list(Callers).map(entry =>
      parseCaller(entry, project.getOrElse(""))
        .toRight(s"$Callers has an entry that is not <service>, <project>/<service> or *: '$entry'")
    )

    val problems =
      Vector(project, service, ownPort, processPort).flatMap(_.left.toOption) ++
        mounts.flatMap(_.left.toOption) ++ callers.flatMap(_.left.toOption)
    if problems.nonEmpty then Left(problems)
    else
      Right(
        ProxySettings(
          project = project.toOption.get,
          service = service.toOption.get,
          port = ownPort.toOption.get,
          processPort = processPort.toOption.get,
          mounts = mounts.flatMap(_.toOption),
          callers = callers.flatMap(_.toOption),
          publicAuthority = env(PublicAuthority).filter(_.nonEmpty),
          namespacePrefix = env(NamespacePrefix).filter(_.nonEmpty).getOrElse("ankka")
        )
      )
