Feature: Recurring timers through restarts and failures
  The platform keeps a recurring timer's period and its next due time in the service's database,
  so a restart moves neither. A recurring timer whose handler fails fires again after its
  backoff, as any timer does, and when its handler then runs without a failure its next due time
  is taken from the due time it failed for, never from the time the handler ran. A recurring
  timer never fires for a due time that has passed: when its service was not running, or its
  handler failed or ran, for longer than a period, it fires once, and its next due time is the
  first still to come that is a whole number of periods after the due time before it.

  Background:
    Given a service "catalog" with a timed action "cleanup"

  Scenario: a recurring timer fires for the due time it had before a restart
    Given a handler "sweep" of "cleanup"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "4 seconds"
    And the timer "sweep-carts" has fired 1 time
    When "catalog" restarts before the next due time of "sweep-carts"
    Then the timer "sweep-carts" fires for the due time it had before the restart
    And each due time it fires for afterwards is one period after the due time before it

  Scenario: a recurring timer whose handler failed takes its next due time from the due time it failed for
    Given a handler "sweep" of "cleanup" that fails 2 times and then runs without a failure
    And the recurring timer "sweep-carts" set for "sweep" with a period of "1 minute"
    When the timer "sweep-carts" has fired 3 times
    Then the timer fired again after a backoff of "3 seconds", and then of "6 seconds"
    And the handler was told that the timer had failed 1 time, and then 2 times
    And the next due time of "sweep-carts" is "1 minute" after the due time it failed for

  Scenario: a recurring timer whose handler keeps failing fires only after each backoff
    Given a handler "sweep" of "cleanup" that fails
    And the recurring timer "sweep-carts" set for "sweep" with a period of "1 second"
    When "10 seconds" have passed since the timer "sweep-carts" first fired
    Then the timer "sweep-carts" has fired no more than 3 times

  Scenario: a recurring timer that missed several periods while its service was not running fires once
    Given a handler "sweep" of "cleanup"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    And the timer "sweep-carts" has fired 1 time
    When "catalog" stops, and starts again "10 seconds" later
    Then the timer "sweep-carts" fires once for the due time it had when "catalog" stopped
    And its next due time is the first still to come that is a whole number of periods after that due time
    And the timer "sweep-carts" does not fire for the due times that passed while "catalog" was not running

  Scenario: a recurring timer whose handler failed for longer than a period does not fire for the due times that passed
    Given a handler "sweep" of "cleanup" that fails 2 times and then runs without a failure
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" has fired 3 times
    Then its next due time is the first still to come that is a whole number of periods after the due time it failed for
    And the timer "sweep-carts" does not fire for the due times that passed while its handler failed

  Scenario: a recurring timer whose handler takes longer than a period does not fire for the due times that passed
    Given a handler "sweep" of "cleanup" that takes "5 seconds"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" fires and its handler finishes
    Then its next due time is the first still to come that is a whole number of periods after the due time it fired for
    And the timer "sweep-carts" does not fire for the due times that passed while its handler ran
