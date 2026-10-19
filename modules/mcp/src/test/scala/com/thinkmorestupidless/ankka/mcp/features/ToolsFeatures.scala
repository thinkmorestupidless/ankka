package com.thinkmorestupidless.ankka.mcp.features

/** `features/mcp-endpoints/tools.feature`, against one service on loopback per scenario. */
class ToolsFeatures extends McpEndpointSteps("../../features/mcp-endpoints/tools.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a request to an MCP endpoint is answered by whichever instance receives it" ->
      "McpEndpointsClusterFeatures (controlPlane, k3s): three instances behind one Service",
    "an MCP endpoint is served at every hostname the service answers at" ->
      "McpEndpointsClusterFeatures (controlPlane, k3s): the derived hostname and a custom one",
    "a paused service's MCP endpoint answers nothing" ->
      "McpEndpointsClusterFeatures (controlPlane, k3s): a paused service"
  )
