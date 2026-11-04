package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import scala.concurrent.duration.DurationInt

/**
 * `features/erasure/`, in process (feature 042): services under `AnkkaTestKit` with the in-memory
 * keyring, or the keyring's own server over loopback, and the control plane's erasure requests,
 * routes and CLI in this JVM. In the control plane's tests rather than the test kit's because an
 * erasure request is the control plane's, and its tests can see the kits, the CLI and the keyring.
 *
 * What only a cluster can show is delegated, by name, to the two k3s suites; what each language's
 * SDK writes is proved by that SDK's suites against the shared fixtures and by the conformance
 * suite. `features/installation/facts.feature`'s erasure scenarios run in
 * `InstallationFactsFeatures`, which runs that file whole.
 */
class ErasureFeatures extends GherkinSuite("../features/erasure") with LogCapturing:

  override val munitTimeout = 4.minutes

  override protected def ranElsewhere: Map[String, String] = Map(
    // A restored database and a restored keyring are a cluster's: CNPG's recovery and the hold.
    "a service restored to before an erasure is not ready until it has applied the erasure log to its own tables" ->
      "ErasureRestoreClusterFeatures, on k3s",
    "an entity recovered from a restored database reads an erased data subject as erased, because the keyring was not restored" ->
      "ErasureRestoreClusterFeatures, on k3s",
    "the keyring restored to before an erasure applies the erasure log before it answers any request" ->
      "ErasureRestoreClusterFeatures, on k3s",
    // A machine outside the installation reaches the keyring through the installation's gateway.
    "a machine outside the installation with a grant that does not allow decryption reads every personal field as erased" ->
      "ErasureClusterFeatures, on k3s, through the gateway",
    "a machine outside the installation with a grant that allows decryption asks the keyring to decrypt each field" ->
      "ErasureClusterFeatures, on k3s, through the gateway",
    "the keyring refuses to decrypt for a machine outside the installation once its grant is revoked or the data subject is erased" ->
      "ErasureClusterFeatures, on k3s, through the gateway",
    // A soft-delete window is the cloud store's (GCS), which only the GCS cluster suite reaches.
    "the erasure request says when the erasure of objects becomes final on an object store with a soft-delete window" ->
      "ObjectStorageGcsClusterFeatures, against a real bucket",
    // Each language writes the envelope itself.
    "an SDK writes the same personal envelope and reads it back into its own personal type" ->
      "each SDK's suite against protocol/fixtures/personal, and ConformanceSuite's personal.* cases",
    "a service written in a language of the JVM writes the personal envelope a service written in Scala writes" ->
      "PersonalJavaSuite (sdk-java) and the Kotlin SDK's PersonalTest",
    // The console is tested by its own Playwright suite against the fake control plane.
    "the console shows the erasure requests of a project with where each one stands and each service's completion" ->
      "console/e2e/tests/erasures.spec.ts, against the fake control plane"
  )
