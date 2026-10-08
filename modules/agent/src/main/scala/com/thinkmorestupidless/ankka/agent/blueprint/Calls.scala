package com.thinkmorestupidless.ankka.agent.blueprint

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString, JsonValueCodec}
import com.thinkmorestupidless.ankka.core.{Codecs, CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, TimerScheduler}

import java.time.Clock
import scala.util.Try

/** What registering a blueprint gave: its version, whether that version is new, and any notes. */
final case class Registered(name: String, version: Int, isNew: Boolean, notes: Vector[Note])

/** One version of a blueprint as a reader lists it. */
final case class VersionInfo(number: Int, digest: String, registeredAt: Long)

/**
 * Calls about blueprints: register one, list a blueprint's versions, read one back. Reached from
 * the `AgentRuntime` that holds what blueprints may name (`agents.blueprints`), available once the
 * service has started, as a `TimerRuntime`'s scheduler is; inject it into endpoints and workflows.
 */
final class BlueprintCalls private[ankka] (
    client: ComponentClient,
    val registry: BlueprintRegistry,
    timers: Option[TimerScheduler],
    clock: Clock
):

  private def entity(name: String) = client.forEventSourcedEntity(EntityId(name))

  /**
   * Checks the blueprint whole and, with no problems, holds it. A refusal is a `CommandError` of
   * `BadRequest` whose message carries every problem; `BlueprintRefusal.problemsOf` reads them
   * back.
   */
  def register(blueprint: Blueprint): Registered =
    BlueprintCheck.check(blueprint, registry) match
      case (problems, _) if problems.nonEmpty =>
        throw BlueprintRefusal.error(blueprint.name, problems)
      case (_, notes) => hold(blueprint, notes)

  /** As `register`, from a blueprint written in JSON. */
  def register(json: String): Registered =
    BlueprintCheck.fromJson(json, registry) match
      case Left(problems)            => throw BlueprintRefusal.error(nameIn(json), problems)
      case Right((blueprint, notes)) => hold(blueprint, notes)

  private def hold(blueprint: Blueprint, notes: Vector[Note]): Registered =
    val accepted = entity(blueprint.name)
      .call(BlueprintEntity.register)
      .invoke(BlueprintEntity.Register(blueprint.canonical, blueprint.digest))
    // Every registration, new version or not: a schedule whose timer was lost is set again.
    ScheduleTimer.onRegistered(client, timers, clock, blueprint)
    Registered(blueprint.name, accepted.number, accepted.isNew, notes)

  /** Every version of a blueprint, oldest first; none for a name never registered. */
  def versions(name: String): Vector[VersionInfo] =
    try
      entity(name)
        .call(BlueprintEntity.get)
        .invoke()
        .versions
        .map(v => VersionInfo(v.number, v.digest, v.registeredAt))
    catch case e: CommandError if e.code == ErrorCode.NotFound => Vector.empty

  /** One version, as it was registered. */
  def version(name: String, number: Int): Blueprint =
    val record = entity(name).call(BlueprintEntity.get).invoke()
    record.version(number) match
      case None =>
        throw CommandError(s"blueprint '$name' has no version $number", ErrorCode.NotFound)
      case Some(v) =>
        Blueprint
          .fromJson(v.canonical)
          .fold(
            e =>
              throw CommandError(
                s"blueprint '$name' version $number does not read: $e",
                ErrorCode.Internal
              ),
            identity
          )

  /** The current version, when the blueprint is held. */
  def current(name: String): Option[Blueprint] =
    versions(name).lastOption.map(v => version(name, v.number))

  private def nameIn(json: String): String =
    com.thinkmorestupidless.ankka.agent.Json
      .parse(json)
      .toOption
      .flatMap(_("name"))
      .flatMap(_.asString)
      .getOrElse("?")

/** The form a refusal takes on the wire: every problem, so the caller fixes them in one round. */
object BlueprintRefusal:
  final case class Refusal(blueprint: String, problems: Vector[Problem])
  given codec: JsonValueCodec[Refusal] = Codecs.make

  private[blueprint] def error(name: String, problems: Vector[Problem]): CommandError =
    CommandError(writeToString(Refusal(name, problems)), ErrorCode.BadRequest)

  /** The problems a refusal carries, when the error is one. */
  def problemsOf(error: CommandError): Option[Vector[Problem]] =
    if error.code != ErrorCode.BadRequest then None
    else Try(readFromString[Refusal](error.getMessage)).toOption.map(_.problems)

  /** The problems, one per line, for a person. */
  def describe(error: CommandError): String =
    problemsOf(error).fold(error.getMessage)(_.mkString("\n"))
