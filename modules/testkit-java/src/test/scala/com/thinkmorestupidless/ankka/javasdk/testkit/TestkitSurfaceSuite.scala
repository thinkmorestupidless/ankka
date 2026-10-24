package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk.SurfaceChecks

/** The Java test kit's surface, held to the same rule as the SDK's (R12). */
class TestkitSurfaceSuite
    extends SurfaceChecks(classOf[AnkkaTestKit], "com.thinkmorestupidless.ankka.javasdk.testkit")
