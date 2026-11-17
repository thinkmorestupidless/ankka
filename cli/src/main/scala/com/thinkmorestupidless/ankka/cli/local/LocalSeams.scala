package com.thinkmorestupidless.ankka.cli.local

/**
 * What the `ankka local` commands reach the machine through (feature 066), replaceable by a suite
 * that drives `Main.run`, as `LocalWeb`'s source is: the engine, how readiness is asked, and
 * whether a port is free.
 */
object LocalSeams:
  @volatile var engine: () => Engine     = () => DockerEngine()
  @volatile var probe: LocalUp.Probe     = Probes.host
  @volatile var isFree: Int => Boolean   = LocalState.isFree
  @volatile var cliVersion: () => String = () =>
    com.thinkmorestupidless.ankka.core.BuildInfo.version

  /** Puts every seam back as the command line has it. */
  def reset(): Unit =
    engine = () => DockerEngine()
    probe = Probes.host
    isFree = LocalState.isFree
    cliVersion = () => com.thinkmorestupidless.ankka.core.BuildInfo.version
