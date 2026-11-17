package com.thinkmorestupidless.ankka.cli.local

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * What `ankka local up` chose once for a project and keeps (feature 066, research R9 and R13), in
 * `.ankka/local/state.json`: the host ports of the database server and the broker, each service's
 * observability port, a process service's ports when it is run from its source, the host ports of a
 * companion's ports that name none, and each service's secret key. A port once chosen is never
 * changed, so a service's address and a container's environment stay what they were across runs,
 * and a secret store reads what it kept. `ankka local down --volumes` removes the file with the
 * data.
 */
final case class LocalState(
    postgresPort: Option[Int] = None,
    kafkaPort: Option[Int] = None,
    observability: Map[String, Int] = Map.empty,
    processPorts: Map[String, Int] = Map.empty,
    companionPorts: Map[String, Map[String, Int]] = Map.empty,
    secretKeys: Map[String, String] = Map.empty
):
  /** Every host port this state holds, so a new choice is none of them. */
  def ports: Set[Int] =
    (postgresPort ++ kafkaPort ++ observability.values ++ processPorts.values ++
      companionPorts.values.flatMap(_.values)).toSet

object LocalState:

  val Directory: String = ".ankka/local"
  val FileName: String  = "state.json"

  /** The database server's and the broker's ports, when they are free. */
  val PreferredPostgres: Int = 5432
  val PreferredKafka: Int    = 9092

  given codec: JsonValueCodec[LocalState] = JsonCodecMaker.make

  def file(root: Path): Path = root.resolve(Directory).resolve(FileName)

  /** The state at `root`, or an empty one when the command has chosen nothing there yet. */
  def read(root: Path): LocalState =
    val at = file(root)
    if !Files.isRegularFile(at) then LocalState()
    else
      try readFromString[LocalState](Files.readString(at, UTF_8))
      catch case _: JsonReaderException => LocalState()

  /**
   * The state at `root` for a command that chooses from it: one that does not read is refused, not
   * replaced, since a new choice would give every service a new secret key and a store kept under
   * the old one would no longer read.
   */
  def readChecked(root: Path): Either[String, LocalState] =
    val at = file(root)
    if !Files.isRegularFile(at) then Right(LocalState())
    else
      try Right(readFromString[LocalState](Files.readString(at, UTF_8)))
      catch
        case e: JsonReaderException =>
          Left(
            s"$Directory/$FileName is not readable: ${e.getMessage.linesIterator.nextOption().getOrElse("")}; " +
              "ankka local down --volumes removes it with what was recorded"
          )

  def write(root: Path, state: LocalState): Unit =
    val at = file(root)
    Files.createDirectories(at.getParent)
    Files.writeString(
      at,
      writeToString(state, WriterConfig.withIndentionStep(2)) + "\n",
      UTF_8
    ): Unit

  /**
   * What a plan needs chosen: every service, the process services, each companion's unnamed ports.
   */
  final case class Wanted(
      services: Vector[String],
      processServices: Vector[String] = Vector.empty,
      companionPorts: Map[String, Vector[Int]] = Map.empty
  )

  /** How the machine answers the questions a choice needs. */
  final case class Choices(
      isFree: Int => Boolean,
      freePort: () => Int,
      newKey: () => String,
      /** Ports a service's descriptor names, which no choice may take. */
      reserved: Set[Int] = Set.empty
  )

  /** `state` with everything `wanted` needs and it lacks chosen; nothing it holds is changed. */
  def completed(state: LocalState, wanted: Wanted, choices: Choices): LocalState =
    var used                                = state.ports ++ choices.reserved
    def choose(preferred: Option[Int]): Int =
      val port = preferred.filter(p => !used(p) && choices.isFree(p)).getOrElse {
        Iterator.continually(choices.freePort()).find(p => !used(p)).get
      }
      used += port
      port
    val postgres = state.postgresPort.getOrElse(choose(Some(PreferredPostgres)))
    val kafka    = state.kafkaPort.getOrElse(choose(Some(PreferredKafka)))
    def each(kept: Map[String, Int], names: Vector[String]): Map[String, Int] =
      names.foldLeft(kept)((m, n) => if m.contains(n) then m else m + (n -> choose(None)))
    val observability = each(state.observability, wanted.services)
    val process       = each(state.processPorts, wanted.processServices)
    val companions    = wanted.companionPorts.foldLeft(state.companionPorts) {
      case (all, (companion, ports)) =>
        val kept = all.getOrElse(companion, Map.empty)
        all + (companion -> ports.foldLeft(kept)((m, p) =>
          if m.contains(p.toString) then m else m + (p.toString -> choose(None))
        ))
    }
    val keys = wanted.services.foldLeft(state.secretKeys)((m, n) =>
      if m.contains(n) then m else m + (n -> choices.newKey())
    )
    LocalState(Some(postgres), Some(kafka), observability, process, companions, keys)

  /** A secret key as the runtime reads `ANKKA_SECRET_KEY`: base64 of 32 random bytes. */
  def randomKey(): String =
    val bytes = new Array[Byte](32)
    java.security.SecureRandom().nextBytes(bytes)
    java.util.Base64.getEncoder.encodeToString(bytes)

  /** A port nothing on this machine holds now, as the operating system chooses one. */
  def freePort(): Int =
    val socket = java.net.ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  /** Whether nothing on this machine holds `port` on loopback or on every interface. */
  def isFree(port: Int): Boolean =
    // With address reuse, as Docker binds: a connection lingering in TIME_WAIT (one a developer's
    // request left a moment before `local down`) is not a holder, and a listener still is. On its
    // own the wildcard bind would succeed beside a listener on loopback alone, so both are asked.
    Vector("0.0.0.0", "127.0.0.1").forall { address =>
      try
        val socket = java.net.ServerSocket()
        try
          socket.setReuseAddress(true)
          socket.bind(java.net.InetSocketAddress(address, port))
          true
        finally socket.close()
      catch case _: java.io.IOException => false
    }
