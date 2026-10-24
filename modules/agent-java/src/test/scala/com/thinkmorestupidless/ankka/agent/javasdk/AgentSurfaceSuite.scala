package com.thinkmorestupidless.ankka.agent.javasdk

import com.thinkmorestupidless.ankka.javasdk.SurfaceChecks

/** The Java agent module's surface, held to the same rule as the SDK's (R12, T019). */
class AgentSurfaceSuite
    extends SurfaceChecks(classOf[Agent], "com.thinkmorestupidless.ankka.agent.javasdk")
