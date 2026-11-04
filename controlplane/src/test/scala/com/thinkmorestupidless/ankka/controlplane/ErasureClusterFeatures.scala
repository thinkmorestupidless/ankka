package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import scala.concurrent.duration.DurationInt

/**
 * `features/erasure/other-projects.feature`'s machine outside the installation on k3s (feature
 * 042): the keyring behind the installation's gateway, a machine's token holding a grant, and the
 * keyring's decryption field by field.
 *
 * Every step is defined as the scenarios are made to pass; until then each scenario fails naming
 * the step it lacks. Off with `-Dankka.cluster.tests=off`, and run by the `cluster` workflow, which
 * finds it because it reads that switch.
 */
class ErasureClusterFeatures
    extends GherkinSuite("../features/erasure/other-projects.feature")
    with LogCapturing:

  override val munitTimeout = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "a consumer in another project with a grant that allows decryption reads the value" ->
      "ErasureProjectsFeatures, two projects in one JVM",
    "a consumer in another project with a grant that does not allow decryption reads the field as erased" ->
      "ErasureProjectsFeatures, two projects in one JVM",
    "an erasure in the producing project reaches what another project kept of the data subject" ->
      "ErasureProjectsFeatures, two projects in one JVM",
    "a revoked grant ends another project's reads of the producing project's data subjects" ->
      "ErasureProjectsFeatures, two projects in one JVM"
  )
