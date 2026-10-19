package com.thinkmorestupidless.ankka.mcp.features

/**
 * `features/mcp-endpoints/observing.feature` on the kit: the trace scenario reads the service's
 * recorder, which is what the local console's trace view reads.
 */
class ObservingFeatures extends McpEndpointSteps("../../features/mcp-endpoints/observing.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a service's report names its MCP endpoints and their tools" ->
      "DeployedMcpTopologyFeatures (controlPlane): the real control plane, the CLI's table and the console's document"
  )
