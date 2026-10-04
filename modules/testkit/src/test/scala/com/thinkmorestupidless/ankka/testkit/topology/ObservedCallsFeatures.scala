package com.thinkmorestupidless.ankka.testkit.topology

/**
 * `features/topology/observed-calls.feature`, run as it is written: each scenario starts the
 * service it describes, has it do what the scenario says over HTTP, and reads its topology as the
 * local console would.
 *
 * One scenario is not run here. A component written in Scala has no host that answers for an
 * instance it could not reach: a call to one waits, and is counted as timed out. The host that does
 * answer is the one for a component in another language, when its instance stops with calls still
 * waiting, and this module cannot see it.
 */
final class ObservedCallsFeatures
    extends TopologySteps("../../features/topology/observed-calls.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a call that reaches no instance is counted as undelivered" -> "sidecar's RemoteEntitySuite"
  )
