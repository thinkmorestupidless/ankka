package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters.*

/**
 * Loads a service's configuration in layers, choosing how its nodes find each other by where it is
 * running.
 *
 * {{{
 *   system properties  >  the service's application.conf  >  ankka-cluster-<mode>.conf  >  reference.conf
 * }}}
 *
 * The overlay is the only layer that knows how a node finds its peers, and the base is the only
 * layer that knows nothing about it. Adding a means of execution is adding an overlay file.
 *
 * Deliberately not `-Dconfig.resource=…`, the usual way to select an alternative configuration:
 * that flag *replaces* `application.conf`. Fine for an application that owns its configuration — a
 * platform running images whose `application.conf` belongs to someone else would be discarding it
 * silently. Here the service's file is loaded, sits above the overlay, and can override any choice
 * the platform made.
 */
object ClusterConfig:

  val ModeVariable: String = "ANKKA_CLUSTER_MODE"

  /** One overlay file per entry, `ankka-cluster-<mode>.conf`. */
  val Modes: Vector[String] = Vector("local", "kubernetes")

  val DefaultMode: String = "local"

  /**
   * @param env
   *   the environment to read the mode and the overlay's substitutions from — the process's own by
   *   default, an explicit map in tests. Only the platform's variables (`ANKKA_*`, `POD_IP`) are
   *   made visible to the configuration.
   * @param application
   *   the service's own configuration; `application.conf` by default.
   */
  def load(
      env: Map[String, String] = sys.env,
      application: Config = ConfigFactory.defaultApplication()
  ): Config =
    ConfigFactory
      .defaultOverrides()
      .withFallback(application)
      .withFallback(overlay(env))
      .withFallback(ConfigFactory.defaultReference())
      .withFallback(platformEnv(env))
      // Once, last. Resolved any earlier, the overlay's ${POD_IP} is looked up before the
      // environment has been merged beneath it.
      .resolve()

  /**
   * Gives a configuration a caller assembled for itself the overlay it does not have.
   *
   * `Ankka.start` passes every config through here. One the loader produced is returned as it is.
   * One built from `ConfigFactory.load()` directly — the test kit does this — carries Pekko's own
   * defaults for the keys the overlay sets, a *fixed* remoting port among them, which two test
   * systems on one machine cannot share; so for such a config the overlay goes **above** it. That
   * is the reverse of `load`'s precedence, and deliberate: a caller who wants to override the
   * platform's choice of mechanism uses `application.conf`, which `load` honours. A hand-built
   * config is the exception path, and there the platform's mechanism wins.
   */
  def layered(config: Config, env: Map[String, String] = sys.env): Config =
    if config.hasPath(ClusterFormation.FormationKey) then config
    else overlay(env).withFallback(config).withFallback(platformEnv(env)).resolve()

  private def overlay(env: Map[String, String]): Config =
    val mode = env.getOrElse(ModeVariable, DefaultMode)
    if !Modes.contains(mode) then
      throw IllegalArgumentException(
        s"$ModeVariable '$mode' is not a known means of execution; expected one of: " +
          Modes.mkString(", ")
      )
    ConfigFactory.parseResources(s"ankka-cluster-$mode.conf")

  // Substitutions such as ${POD_IP} resolve against the merged tree, so the platform's variables go
  // in at the bottom as plain keys — the bottom, so nothing real is ever shadowed by them.
  private def platformEnv(env: Map[String, String]): Config =
    ConfigFactory.parseMap(
      env.filter((key, _) => key.startsWith("ANKKA_") || key == "POD_IP").asJava
    )
