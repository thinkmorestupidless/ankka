package nakka.controlplane.api

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
object Compatibility:

  def supports(platform: Version, runtime: Version): Boolean =
    runtime.major == platform.major &&
      runtime.minor <= platform.minor &&
      runtime.minor >= platform.minor - 1

  /** The supported range as a phrase, for a refusal and for the README. */
  def describe(platform: Version): String =
    val low = math.max(0, platform.minor - 1)
    val range =
      if low == platform.minor then s"${platform.major}.${platform.minor}.x"
      else s"${platform.major}.$low.x–${platform.major}.${platform.minor}.x"
    s"runtimes $range (platform $platform)"
