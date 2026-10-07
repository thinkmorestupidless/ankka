Feature: Timers
  A timer is a call the platform makes later to a handler of a timed action. It is set under a
  name with a due time, the service's database keeps it, and the platform removes it once its
  handler has run without a failure. A timer set under a name that has one replaces it, and that
  holds for a handler that sets the timer that ran it: what the handler set is the next timer,
  and the platform keeps it. The handler is told the name of the timer that ran it, how many
  times it has failed, and the due time it is run for.

  Background:
    Given a service "orders" with a timed action "reminders"

  Scenario: a timer is removed once its handler has run
    Given a handler "nudge" of "reminders"
    And the timer "nudge-c1" set for "nudge"
    When the timer "nudge-c1" fires and its handler finishes
    Then "orders" has no timer "nudge-c1"
    And the timer "nudge-c1" does not fire again

  Scenario: a timer whose handler sets it again fires again
    Given a handler "nudge" of "reminders" that sets the timer "nudge-c1" for "nudge" again, due "1 second" later
    And the timer "nudge-c1" set for "nudge"
    When the timer "nudge-c1" fires
    Then the timer "nudge-c1" fires a second time within "3 seconds"

  Scenario: a timer its handler set again is kept with the due time the handler gave it
    Given a handler "nudge" of "reminders" that sets the timer "nudge-c1" for "nudge" again, due "1 minute" later
    And the timer "nudge-c1" set for "nudge"
    When the timer "nudge-c1" fires and its handler finishes
    Then "orders" has the timer "nudge-c1"
    And the due time of "nudge-c1" is "1 minute" after the handler set it

  Scenario: a timer whose handler fails is kept and fires again after its backoff
    Given a handler "nudge" of "reminders" that fails
    And the timer "nudge-c1" set for "nudge"
    When the timer "nudge-c1" fires
    Then "orders" has the timer "nudge-c1"
    And the timer "nudge-c1" fires again after a backoff of "3 seconds"
    And the handler is then told that the timer has failed 1 time

  Scenario: a handler is told the due time of the timer that ran it
    Given a handler "nudge" of "reminders"
    And the timer "nudge-c1" set for "nudge"
    When the timer "nudge-c1" fires
    Then the handler is told the due time the timer "nudge-c1" was set with

  Scenario: a timer that fires again after a failure is told the same due time
    Given a handler "nudge" of "reminders" that fails 1 time and then runs without a failure
    And the timer "nudge-c1" set for "nudge"
    When the timer "nudge-c1" has fired 2 times
    Then the handler was told the same due time each time
