package com.thinkmorestupidless.ankka.testkit.timers

/**
 * `features/timers/restarts-and-failures.feature`, run as it is written against a real service. A
 * restart is the test kit's, which drops everything from memory; an outage is a restart that stays
 * down for as long as the scenario says, with the database up throughout.
 */
final class TimerRecoveryFeatures
    extends TimerSteps("../../features/timers/restarts-and-failures.feature")
