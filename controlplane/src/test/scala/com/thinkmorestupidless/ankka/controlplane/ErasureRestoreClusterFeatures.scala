package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import scala.concurrent.duration.DurationInt

/**
 * `features/erasure/restores.feature` on k3s (feature 042): a service's database and the keyring's
 * restored by CNPG to a moment before an erasure, the restore hold, and the erasure log replayed
 * from the control plane's copy and the bucket's.
 *
 * Every step is defined as the scenarios are made to pass; until then each scenario fails naming
 * the step it lacks. Off with `-Dankka.cluster.tests=off`, and run by the `cluster` workflow, which
 * finds it because it reads that switch.
 */
class ErasureRestoreClusterFeatures
    extends GherkinSuite("../features/erasure/restores.feature")
    with LogCapturing:

  override val munitTimeout = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "the keyring applies the union of both copies of the erasure log and reports the copy that was behind" ->
      "ErasureFeatures, against the keyring's server on loopback",
    "an erasure whose erasure log write failed destroys no subject key" ->
      "ErasureFeatures, with the bucket's copy refusing"
  )
