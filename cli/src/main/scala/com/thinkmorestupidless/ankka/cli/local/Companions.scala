package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.cli.local.LocalFile.{Companion, Token}

import java.nio.file.Files
import scala.collection.immutable.ListMap

/**
 * A project's companions on a developer's machine (feature 066, contracts/project-files.md): what
 * is wrong with them, the ports chosen for them, the compose service each runs as, and the
 * addresses the tokens in a value are written as.
 */
private[local] object Companions:

  private val DnsLabel = "[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?".r

  /** The companions the selected services need: one named by none of them is not started. */
  def needed(project: LocalProject, selected: Vector[String]): Vector[Companion] =
    project.local.companions.filter(c => selected.exists(c.needed))

  /** The ports a companion leaves to the command to choose, by companion. */
  def unnamedPorts(project: LocalProject): Map[String, Vector[Int]] =
    project.local.companions
      .map(c => c.name -> c.ports.filter(_.host.isEmpty).map(_.port))
      .filter(_._2.nonEmpty)
      .toMap

  /** Where a companion's container port is on the machine: as it says, or as the command chose. */
  def hostPort(state: LocalState, companion: Companion, port: Int): Option[Int] =
    companion.ports
      .find(_.port == port)
      .flatMap(p =>
        p.host.orElse(state.companionPorts.get(companion.name).flatMap(_.get(port.toString)))
      )

  private def first(companion: Companion): Option[Int] = companion.ports.headOption.map(_.port)

  /**
   * A token in a service's value, written as the service reaches the companion from `host`'s side.
   */
  def forService(project: LocalProject, state: LocalState, host: String = LocalPlan.HostAddress)(
      token: Token
  ): Option[String] =
    def address(name: String) =
      project.local.companions.find(_.name == name).flatMap { c =>
        first(c).flatMap(hostPort(state, c, _)).map(p => s"$host:$p")
      }
    token match
      case Token.CompanionAddress(name) => address(name)
      case Token.CompanionUrl(name)     => address(name).map("http://" + _)
      case Token.Database(_)            => None

  /** A token in a companion's value: another companion by its container, its own database. */
  def forCompanion(project: LocalProject, companion: Companion)(token: Token): Option[String] =
    def address(name: String) =
      project.local.companions.find(_.name == name).flatMap(c => first(c).map(p => s"${c.name}:$p"))
    val database = s"${LocalPlan.Postgres}:${LocalPlan.PostgresPort}/${companion.name}"
    token match
      case Token.CompanionAddress(name) => address(name)
      case Token.CompanionUrl(name)     => address(name).map("http://" + _)
      case Token.Database(field)        =>
        field match
          case "host"     => Some(LocalPlan.Postgres)
          case "port"     => Some(LocalPlan.PostgresPort.toString)
          case "name"     => Some(companion.name)
          case "user"     => Some(LocalPlan.DatabaseUser)
          case "password" => Some(LocalPlan.DatabasePassword)
          case "url"      =>
            Some(s"postgres://${LocalPlan.DatabaseUser}:${LocalPlan.DatabasePassword}@$database")
          case "jdbc" => Some(s"jdbc:postgresql://$database")
          case _      => None

  /** The compose service a companion runs as. */
  def service(
      project: LocalProject,
      companion: Companion,
      state: LocalState,
      publish: Vector[(Int, Int)] => Vector[String]
  ): Compose.Service =
    Compose.Service(
      image = companion.image,
      command = companion.command,
      environment = ListMap.from(
        companion.env.toVector
          .sortBy(_._1)
          .map((k, v) => k -> LocalFile.fill(v, forCompanion(project, companion)))
      ),
      ports =
        publish(companion.ports.flatMap(p => hostPort(state, companion, p.port).map(_ -> p.port))),
      volumes = companion.files.map(f =>
        s"${project.root.resolve(f.from).toAbsolutePath.normalize}:${f.to}:ro"
      ),
      dependsOn =
        if companion.database then
          ListMap(LocalPlan.Postgres -> Compose.Dependency(Compose.Healthy))
        else ListMap.empty,
      extraHosts = Vector(Compose.HostGateway),
      memLimit = companion.memory
    )

  /** The check a companion is ready by, asked from the host. */
  def ready(companion: Companion, state: LocalState): Option[LocalPlan.ReadyCheck] =
    companion.ready.flatMap { r =>
      r.http
        .flatMap(h => hostPort(state, companion, h.port).map(LocalPlan.ReadyCheck.Http(_, h.path)))
        .orElse(r.tcp.flatMap(hostPort(state, companion, _)).map(LocalPlan.ReadyCheck.Tcp(_)))
    }

  /**
   * Every problem of the companions and of the tokens in every value (contracts/project-files.md).
   */
  def problems(project: LocalProject, machine: LocalPlan.Machine): Vector[String] =
    val all               = project.local.companions
    val names             = all.map(_.name).toSet
    val services          = project.services
    def the(c: Companion) = s"the companion \"${c.name}\""
    val twice             = all
      .map(_.name)
      .groupBy(identity)
      .collect {
        case (name, found) if found.size > 1 => s"the companion \"$name\" is named twice"
      }
      .toVector
      .sorted
    val servicePorts = services.flatMap { n =>
      val s = project.descriptor(n).service
      (Option.when(s.http)(s.port).toVector ++ Option.when(s.grpc)(s.grpcPort)).map(_ -> n)
    }
    val hostPorts = all.flatMap(c => c.ports.flatMap(_.host).map(_ -> c.name))
    val each      = all.distinctBy(_.name).flatMap { c =>
      val name =
        if services.contains(c.name) then Vector(s"${the(c)} has the name of a service")
        else if LocalPlan.Reserved.contains(c.name) then
          Vector(s"${the(c)} has the name of what the command line runs for every project")
        else if DnsLabel.matches(c.name) then Vector.empty
        else Vector(s"${the(c)} is not a valid name")
      val ports = if c.ports.isEmpty then Vector(s"${the(c)} declares no port") else Vector.empty
      val ready = c.ready match
        case None => Vector(s"${the(c)} declares no ready check")
        case Some(r) if r.http.isDefined == r.tcp.isDefined =>
          Vector(s"${the(c)} declares no ready check")
        case Some(r) =>
          r.port
            .filterNot(p => c.ports.exists(_.port == p))
            .map(p =>
              s"${the(c)} is asked whether it is ready on port $p, which it does not declare"
            )
            .toVector
      val usesDatabase =
        c.env.values.exists(v => LocalFile.tokens(v).known.exists(_.isInstanceOf[Token.Database]))
      val database =
        if usesDatabase && !c.database then
          Vector(s"${the(c)} uses {{database...}} and asks for no database")
        else Vector.empty
      val mounts = c.files.collect {
        case f if !Files.exists(project.root.resolve(f.from)) =>
          s"${the(c)} mounts ${f.from}, which does not exist"
      }
      val clashes = c.ports.flatMap(_.host).flatMap { port =>
        val other = servicePorts.collect { case (p, n) if p == port => n } ++
          hostPorts.collect { case (p, n) if p == port && n != c.name => n }
        other.headOption match
          case Some(o) => Some(s"${the(c)} names port $port, which \"$o\" also names")
          case None    =>
            machine.holder(port) match
              case None | Some(LocalPlan.Holder.Ours(_)) => None
              case Some(_) => Some(s"${the(c)} names port $port, which is in use on this machine")
      }
      val needing = c.neededBy.toVector.flatten
        .filterNot(services.contains)
        .map(n =>
          s"local.json names \"$n\" as needing ${the(c)}, which is not a service of ${project.name}"
        )
      name ++ ports ++ ready ++ database ++ mounts ++ clashes ++ needing
    }
    def undeclared(owner: String, values: Iterable[String]) =
      values.toVector
        .flatMap(v => LocalFile.tokens(v).known)
        .collect {
          case Token.CompanionAddress(n) if !names(n) => n
          case Token.CompanionUrl(n) if !names(n)     => n
        }
        .distinct
        .map(n =>
          s"local.json names the companion \"$n\" in a value of \"$owner\", and declares no such companion"
        )
    val tokens = services.flatMap(s => undeclared(s, project.local.valuesFor(s).values)) ++
      all.distinctBy(_.name).flatMap(c => undeclared(c.name, c.env.values))
    twice ++ each ++ tokens
