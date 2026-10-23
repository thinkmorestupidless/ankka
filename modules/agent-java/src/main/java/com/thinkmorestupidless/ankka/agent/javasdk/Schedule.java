package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * When a scheduled blueprint runs. A run missed while the service was down is caught up once, for
 * the whole missed period, unless the schedule asks for one run per missed period.
 */
public interface Schedule {

  static Schedule weekly(DayOfWeek day, LocalTime time, ZoneId zone) {
    return AgentBridge.weekly(day, time, zone);
  }

  static Schedule everyHours(int hours, ZoneId zone) {
    return AgentBridge.everyHours(hours, zone);
  }

  static Schedule everyDays(int days, ZoneId zone) {
    return AgentBridge.everyDays(days, zone);
  }

  /** This schedule, catching up with one run for each period missed. */
  Schedule perMissedPeriod();
}
