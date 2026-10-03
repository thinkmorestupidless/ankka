package com.thinkmorestupidless.ankka.testkit.topology

/**
 * `features/topology/other-services.feature`, run as it is written: each scenario starts the
 * service it describes beside the other services it calls, has its endpoint call them over HTTP,
 * and reads its topology as the local console would.
 */
final class OtherServicesFeatures
    extends TopologySteps("../../features/topology/other-services.feature")
