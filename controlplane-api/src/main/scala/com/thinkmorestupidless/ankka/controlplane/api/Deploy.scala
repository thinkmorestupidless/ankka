package com.thinkmorestupidless.ankka.controlplane.api

/**
 * The one field of a descriptor that is genuinely different on every build.
 *
 * `ankka services deploy <service> <image>` takes the image from the command line — Akka's shape,
 * where `akka services deploy SERVICE IMAGE` does the same — and everything else from the
 * descriptor, which stays exactly as its author checked it in. The replacement lives here rather
 * than in the CLI so that the rule is stated once, beside the descriptor it applies to, and is
 * validated by the same `problems` every other path uses.
 */
object Deploy:

  extension (descriptor: ServiceDescriptor)
    /** The same descriptor, with this image. Nothing else changes. */
    def withImage(image: String): ServiceDescriptor =
      descriptor.copy(service = descriptor.service.copy(image = image))

  /**
   * Why this descriptor cannot be deployed as `service` with `image`, or nothing.
   *
   * The name check is the guard against a workflow pointed at the wrong file: a deploy that
   * silently applied a different service than the one named on the command line would be a rollout
   * nobody asked for. Applied by the CLI before any request, so a mistake costs no round trip.
   */
  def problems(descriptor: ServiceDescriptor, service: String, image: String): Vector[String] =
    val nameProblems =
      if descriptor.name != service then
        Vector(s"the descriptor names '${descriptor.name}', not '$service'")
      else Vector.empty
    val imageProblems =
      if image.isEmpty then Vector("an image must be given")
      else if image.exists(_.isWhitespace) then
        Vector(s"an image reference contains no whitespace: '$image'")
      else Vector.empty
    // The descriptor's own rules last, and against the descriptor as it will be sent — so an image
    // that is invalid for a reason `ServiceSpec` knows about is reported by the code that owns it.
    nameProblems ++ imageProblems ++
      (if imageProblems.isEmpty then descriptor.withImage(image).problems else Vector.empty)
