package com.thinkmorestupidless.ankka.testkit.timers

/**
 * `features/timers/testing.feature`: a test reads the due times a recurring timer ran for from the
 * test kit's probe. The steps are the ones every timer feature uses, which is the point: they read
 * the probe too.
 */
final class TimerTestingFeatures extends TimerSteps("../../features/timers/testing.feature")
