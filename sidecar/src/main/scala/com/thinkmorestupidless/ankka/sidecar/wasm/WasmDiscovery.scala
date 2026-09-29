package com.thinkmorestupidless.ankka.sidecar.wasm

import ankka.protocol.v1.discovery.{Component, Kind, SidecarInfo}
import ankka.protocol.v1.wasm.WasmSpec
import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sidecar.Discovery
import com.thinkmorestupidless.ankka.sidecar.Discovery.{Discovered, Shape}
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Discovery from a module: `SidecarInfo` in through `ankka1_discover`, `WasmSpec` out.
 *
 * The spec is held to exactly the rules a process's is (`Discovery.validate`), and to the few a
 * module adds: the ABI version it declares must be the one its exports carry; a stateful component
 * must be declared, and of a kind that has state; nothing may stream, since a module answers a call
 * whole; no autonomous agent, whose task results the ABI cannot yet check; and the module must
 * export what each declared component needs. There is no `ReportError` into a module, so every
 * problem goes to the runtime's log, all at once.
 */
object WasmDiscovery:

  private val log = LoggerFactory.getLogger(getClass)

  private val StatefulKinds: Set[Kind] =
    Set(Kind.EVENT_SOURCED_ENTITY, Kind.KEY_VALUE_ENTITY, Kind.WORKFLOW)

  def discover(
      instance: GuestInstance,
      module: LoadedModule,
      runtimeVersion: String,
      protocolVersion: String = Discovery.ProtocolVersion
  ): Either[Vector[String], Discovered] =
    val answered = instance
      .call(Abi.Prefix + "discover", SidecarInfo(protocolVersion, runtimeVersion).toByteArray)
      .left
      .map(f => Vector(s"the module's discovery failed: ${f.message}"))
      .flatMap(bytes =>
        Try(WasmSpec.parseFrom(bytes)).toEither.left
          .map(e => Vector(s"the module's discovery answer is not a WasmSpec: ${e.getMessage}"))
      )
    val result = answered.flatMap(validate(_, module.exports, protocolVersion))
    result.left.foreach { problems =>
      log.error(problems.mkString("the runtime refused the module:\n  - ", "\n  - ", ""))
    }
    result

  /** Pure: what is wrong with a module's answer, given what it exports. */
  def validate(
      wasm: WasmSpec,
      exports: Set[String],
      protocolVersion: String = Discovery.ProtocolVersion
  ): Either[Vector[String], Discovered] =
    val spec     = wasm.getSpec
    val problems = Vector.newBuilder[String]

    if wasm.abiVersion != Abi.Version then
      problems += s"the module declares ABI version '${wasm.abiVersion}' and exports version " +
        s"${Abi.Version} ('${Abi.Prefix}'); they must be the same"

    val declared = spec.components.map(c => c.id -> c).toMap
    wasm.stateful.foreach { id =>
      declared.get(id) match
        case None => problems += s"'$id' is declared stateful but is not a declared component"
        case Some(c) if !StatefulKinds.contains(c.kind) =>
          problems += s"'$id' is declared stateful but is a ${c.kind}, which has no state to keep"
        case _ => ()
    }

    spec.components.foreach { c =>
      if c.kind == Kind.AUTONOMOUS_AGENT then
        problems += s"component '${c.id}' is an autonomous agent, which a module cannot declare: " +
          "the ABI has no export to check a task's result yet"
      c.handlers.filter(_.streaming).foreach { h =>
        problems += s"component '${c.id}': handler '${h.name}' streams, and a module answers a " +
          "call whole; declare it without streaming"
      }
      needs(c, wasm.stateful.contains(c.id)).filterNot(exports.contains).foreach { missing =>
        problems += s"component '${c.id}' needs the export '$missing', which the module does not have"
      }
    }
    spec.endpoints.foreach { e =>
      e.routes.filter(_.streaming).foreach { r =>
        problems += s"endpoint '${e.id}': route '${r.id}' streams, and a module answers a " +
          "request whole; declare it without streaming"
      }
      if !exports.contains(Abi.Prefix + "http") then
        problems += s"endpoint '${e.id}' needs the export '${Abi.Prefix}http', which the module " +
          "does not have"
    }

    val own = problems.result()
    Discovery.validate(spec, protocolVersion) match
      case Left(shared)             => Left(own ++ shared)
      case Right(_) if own.nonEmpty => Left(own)
      case Right(discovered) =>
        val shapes =
          wasm.stateful.flatMap(id => ComponentId.parse(id).toOption.map(_ -> Shape.Stateful))
        Right(discovered.copy(shapes = shapes.toMap))

  /** The exports a component needs beyond the three every module has. */
  private def needs(c: Component, stateful: Boolean): Vector[String] =
    val byKind = c.kind match
      case Kind.EVENT_SOURCED_ENTITY => Vector("handle", "fold")
      case Kind.KEY_VALUE_ENTITY     => Vector("handle")
      case Kind.WORKFLOW             => Vector("handle", "run_step")
      case Kind.VIEW                 => Vector("view")
      case Kind.CONSUMER             => Vector("consumer")
      case Kind.TIMED_ACTION         => Vector("timed_action")
      case Kind.AGENT =>
        val agent = c.getAgent
        Vector("plan") ++
          Option.when(agent.tools.nonEmpty)("invoke_tool") ++
          Option.when(agent.guardrails.nonEmpty)("check_guardrail")
      case _ => Vector.empty
    (byKind ++ Option.when(stateful)("close")).map(Abi.Prefix + _)
