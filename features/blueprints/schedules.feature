Feature: Runs on a schedule
  A blueprint may carry a schedule, from which its due times follow. At each due time the platform
  starts one run, whose input is the period since the previous due time. Periods meet with no gap
  and no overlap.

  Background:
    Given the blueprint "digest" with a schedule of weekly on "Sunday" at "20:00" in the time zone "Europe/London"
    And a service whose clock a test moves

  Scenario: a scheduled blueprint starts a run at each due time
    When the clock is moved through three Sundays at "20:00"
    Then three runs of "digest" are started, one at each due time

  Scenario: a scheduled run's input is the period since the previous due time
    Given a run of "digest" started at the due time on "2026-10-11"
    When the clock reaches the due time on "2026-10-18"
    Then a run of "digest" is started whose period is from "2026-10-11 20:00" to "2026-10-18 20:00"

  Scenario: periods meet with no gap and no overlap
    Given three runs of "digest" started at three due times in a row
    When a reader lists the runs of "digest"
    Then each run's period starts where the previous run's period ended

  Scenario: due times missed while the service was down start one run covering them all
    Given a run of "digest" started at the due time on "2026-10-04"
    And the service stopped from "2026-10-10" to "2026-10-20"
    When the service starts on "2026-10-20"
    Then one run of "digest" is started
    And its period is from "2026-10-04 20:00" to "2026-10-18 20:00"

  Scenario: a schedule that asks for one run per missed period starts one for each, oldest first
    Given the schedule of "digest" asks for one run per missed period
    And a run of "digest" started at the due time on "2026-10-04"
    And the service stopped from "2026-10-10" to "2026-10-20"
    When the service starts on "2026-10-20"
    Then two runs of "digest" are started, the older first
    And their periods are from "2026-10-04 20:00" to "2026-10-11 20:00" and from "2026-10-11 20:00" to "2026-10-18 20:00"

  Scenario: a weekly cadence keeps its time of day across a change of the clocks
    Given the clocks in "Europe/London" go back on "2026-10-25"
    When the clock reaches the due time on "2026-11-01"
    Then the run of "digest" is started at "20:00" in "Europe/London"
    And its period is one hour longer than a week

  Scenario: a scheduled run uses the version current at its due time
    Given the blueprint "digest" held at blueprint version 1
    When the service registers blueprint version 2 of "digest" before the next due time
    Then the run started at the next due time names blueprint version 2

  Scenario: runs that catch up after an outage use the version current when the service is back
    Given a run of "digest" started at the due time on "2026-10-04" at blueprint version 1
    And the service stopped from "2026-10-10" to "2026-10-20"
    When the service starts on "2026-10-20" carrying blueprint version 2 of "digest"
    Then the run started when the service is back names blueprint version 2

  Scenario: a version without a schedule stops the schedule
    When the service registers a blueprint version of "digest" without a schedule
    And the clock is moved past the next Sunday at "20:00"
    Then no run of "digest" is started

  Scenario: a scheduled run is started once however many instances the service has
    Given the service running as three instances
    When the clock reaches the next due time
    Then one run of "digest" is started
