package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables

/**
 * Where the installation's model server is (feature 053), as its component tells the operator:
 *
 *   - `address`: the door services call, given to every platform container as
 *     `ANKKA_MODELS_ADDRESS`
 *   - `statusUrl`: the keeper's plain status port, which the operator alone may reach and reads to
 *     learn which models are served
 *   - `namespace`: where the model server runs
 *
 * `None` in `Settings.models` is an installation without one: nothing is rendered for it, and a
 * service that names an installation model is failed saying the cluster has no model server.
 */
final case class ModelServerSettings(address: String, statusUrl: String, namespace: String)

object ModelServerSettings:

  /** Each setting: its system property, then the variable the model server's component sets. */
  val Variables: Vector[(String, String)] = Vector(
    "ankka.operator.models-address"    -> PlatformVariables.ModelsAddress,
    "ankka.operator.models-status-url" -> "ANKKA_MODELS_STATUS_URL",
    "ankka.operator.models-namespace"  -> "ANKKA_MODELS_NAMESPACE"
  )

  /**
   * All three, or none, as the broker's are. Some and not others is a component installed by
   * halves; the operator refuses to start rather than guess, naming what is missing.
   */
  def read(lookup: (String, String) => Option[String]): Option[ModelServerSettings] =
    Variables.map(lookup.tupled) match
      case Vector(Some(address), Some(statusUrl), Some(namespace)) =>
        Some(ModelServerSettings(address, statusUrl, namespace))
      case values if values.forall(_.isEmpty) => None
      case values =>
        val missing = Variables.zip(values).collect { case ((_, variable), None) => variable }
        throw IllegalStateException(
          s"the model server is configured by halves: ${missing.mkString(", ")} " +
            s"${if missing.size == 1 then "is" else "are"} not set; set all three or none"
        )
