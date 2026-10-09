package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

/**
 * `features/erasure/languages.feature`: every scenario needs a process or a module in another
 * language behind the sidecar, which this module cannot start. Each language's run is the
 * conformance suite's `personal.*` cases against that language's reference (`uv run conformance`,
 * `npm run conformance`, `./conformance.sh` in both guest shapes), and each SDK's own test of
 * `protocol/fixtures/personal`; this suite reports the scenarios as run there rather than skipping
 * them silently.
 */
class LanguagesFeatures
    extends GherkinSuite("../../features/erasure/languages.feature")
    with LogCapturing:

  // A def: the base class reads `ranElsewhere` while it is constructed, before a val here is set.
  private def where =
    "the conformance suite's personal.* cases against each language's reference (uv run " +
      "conformance, npm run conformance, ./conformance.sh) and each SDK's test of " +
      "protocol/fixtures/personal (test_personal.py, personal.test.ts, tests/personal.rs)"

  override protected def ranElsewhere: Map[String, String] = Map(
    "an SDK writes the same personal envelope and reads it back into its own personal type" -> where
  )
