package com.thinkmorestupidless.ankka.controlplane.api

/** A platform or runtime version: the `MAJOR.MINOR.PATCH` prefix of what sbt-dynver produces. */
final case class Version(major: Int, minor: Int, patch: Int):
  override def toString: String = s"$major.$minor.$patch"

object Version:

  private val Shape = """^(\d+)\.(\d+)\.(\d+)(?:[+-].*)?$""".r

  /** `0.2.0`, `0.2.0+3-abc1234-SNAPSHOT` → `Version(0, 2, 0)`; anything else is a message. */
  def parse(text: String): Either[String, Version] = text match
    case Shape(major, minor, patch) => Right(Version(major.toInt, minor.toInt, patch.toInt))
    case other =>
      Left(
        s"version '$other' is not MAJOR.MINOR.PATCH (optionally followed by +build or -SNAPSHOT)"
      )

/**
 * Which application runtimes a platform will run.
 *
 * The platform — operator, control plane, CLI — is released as one version, and the runtime an
 * application brings in its image is another. They may differ; this is the rule for how far.
 * Deliberately coarse: same major, minor within one below the platform's. A matrix with per-feature
 * negotiation is a later release's problem, and a rule a person can hold in their head is worth
 * more than a precise one nobody reads (feature 006, research R3).
 *
 * Checked when the control plane projects a service (`ServiceProjection`), so an unsupported
 * declaration never reaches the operator and no pod starts.
 */
/** `MAJOR.MINOR`: the sidecar protocol's version (feature 009). */
final case class ProtocolVersion(major: Int, minor: Int):
  override def toString: String = s"$major.$minor"

object ProtocolVersion:
  private val Shape = """^(\d+)\.(\d+)$""".r

  def parse(text: String): Either[String, ProtocolVersion] = text match
    case Shape(major, minor) => Right(ProtocolVersion(major.toInt, minor.toInt))
    case other               => Left(s"version '$other' is not MAJOR.MINOR")

/**
 * The sidecar protocol this platform speaks. Written once here and once in `protocol/README.md`;
 * the sidecar's `Discovery.ProtocolVersion` is the same string.
 */
object Protocol:
  /**
   * 1.1 added the caller to forwarded requests and caller-naming ACLs to discovery. 1.2 added the
   * autonomous agent: a component kind, its definition in discovery, and the task-rule check. 1.3
   * added a consumer's reply of several messages, each under its own record key, and the
   * `ankka.protocol` entry on a consumer's request that says the runtime accepts it.
   */
  val version: ProtocolVersion = ProtocolVersion(1, 3)

object Compatibility:

  /** Same major, and the SDK's minor no later than the platform's: a minor only ever adds. */
  def supportsProtocol(platform: ProtocolVersion, declared: ProtocolVersion): Boolean =
    declared.major == platform.major && declared.minor <= platform.minor

  def describeProtocol(platform: ProtocolVersion): String =
    s"protocols ${platform.major}.0–${platform.major}.${platform.minor} (platform $platform)"

  /**
   * The oldest runtime that speaks mutual TLS (feature 014). A runtime below it cannot form a
   * cluster with the certificates the operator mounts, nor be reached by the gateway, so the "one
   * minor below" rule does not reach across it.
   *
   * Applied only once the platform is itself at or past it: until the release that introduces it is
   * tagged, the platform's own builds are versioned below it, and a floor they could not meet would
   * refuse every image built from the same commit.
   */
  val MinimumRuntime: Version = Version(0, 8, 0)

  private def atLeast(v: Version, floor: Version): Boolean =
    Ordering[(Int, Int, Int)]
      .gteq((v.major, v.minor, v.patch), (floor.major, floor.minor, floor.patch))

  def supports(platform: Version, runtime: Version): Boolean =
    runtime.major == platform.major &&
      runtime.minor <= platform.minor &&
      runtime.minor >= platform.minor - 1 &&
      (!atLeast(platform, MinimumRuntime) || atLeast(runtime, MinimumRuntime))

  /**
   * Why `runtime` is refused, as a detail a person can act on: the floor when it is the floor that
   * refuses, the range otherwise.
   */
  def refusal(platform: Version, runtime: Version): String =
    if atLeast(platform, MinimumRuntime) && !atLeast(runtime, MinimumRuntime) then
      s"runtime $runtime predates mutual TLS; this platform requires $MinimumRuntime or later"
    else s"runtime $runtime is outside the platform's supported range: ${describe(platform)}"

  /** The supported range as a phrase, for a refusal and for the README. */
  def describe(platform: Version): String =
    val low = math.max(0, platform.minor - 1)
    val range =
      if low == platform.minor then s"${platform.major}.${platform.minor}.x"
      else s"${platform.major}.$low.x–${platform.major}.${platform.minor}.x"
    s"runtimes $range (platform $platform)"
