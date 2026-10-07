Feature: Recurring timers
  A recurring timer is a timer with a period. It is set with its first due time, as any timer
  is, and with its period. When its handler has run, the platform gives it its
  next due time: the due time it fired for and one period more, never the time its handler
  finished and one period more. So however long a handler takes within a period, the due times
  stay one period apart, and no handler has to set the timer again. It fires until it is
  cancelled or replaced. A recurring timer set again with the same handler and the same period
  is not replaced: it keeps its next due time, so a service may set its recurring timers each
  time it starts.

  Background:
    Given a service "catalog" with a timed action "cleanup"
    And a handler "sweep" of "cleanup"

  Scenario: a recurring timer is first due when it was set to be, and then once for each period
    Given the recurring timer "sweep-carts" set for "sweep", due "1 second" later, with a period of "3 seconds"
    When the timer "sweep-carts" has fired 3 times
    Then the first due time it fired for is "1 second" after it was set
    And each due time after the first is "3 seconds" after the due time before it

  Scenario: a recurring timer set to be due at once fires at once, and then once for each period
    Given the recurring timer "sweep-carts" set for "sweep", due "0 seconds" later, with a period of "3 seconds"
    When the timer "sweep-carts" has fired 2 times
    Then the first due time it fired for is the time it was set
    And the second due time is "3 seconds" after the first

  Scenario: a recurring timer fires once for each period
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" has fired 5 times
    Then each due time it fired for is one period after the due time before it

  Scenario: however long a handler takes, the next due time is one period after the due time before it
    Given the handler "sweep" takes "1500 milliseconds"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" fires and its handler finishes
    Then the next due time of "sweep-carts" is "2 seconds" after the due time it fired for

  Scenario: a recurring timer's handler is told the due time of each time it fires
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" has fired 3 times
    Then the handler "sweep" was told 3 due times
    And each due time it was told is one period after the due time before it

  Scenario: a recurring timer exists after it has fired
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "1 minute"
    And the timer "sweep-carts" has fired 1 time
    When a handler of "catalog" asks whether the timer "sweep-carts" exists
    Then the handler is told that the timer "sweep-carts" exists

  Scenario: a cancelled recurring timer does not fire again
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    And the timer "sweep-carts" has fired 1 time
    When a handler of "catalog" cancels the timer "sweep-carts"
    Then "catalog" has no timer "sweep-carts"
    And the timer "sweep-carts" does not fire again

  Scenario: a recurring timer whose handler cancels it does not fire again
    Given a handler "sweep-once" of "cleanup" that cancels the timer "sweep-carts"
    And the recurring timer "sweep-carts" set for "sweep-once" with a period of "2 seconds"
    When the timer "sweep-carts" fires and its handler finishes
    Then "catalog" has no timer "sweep-carts"
    And the timer "sweep-carts" does not fire again

  Scenario: a timer with no period set under a recurring timer's name replaces it
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When a handler of "catalog" sets the timer "sweep-carts" for "sweep" with no period
    Then the timer "sweep-carts" fires once more, at the due time the handler gave it
    And "catalog" then has no timer "sweep-carts"

  Scenario: a recurring timer set under the name of a timer with no period replaces it
    Given the timer "sweep-carts" set for "sweep" with no period
    When a handler of "catalog" sets the recurring timer "sweep-carts" for "sweep" with a period of "2 seconds"
    Then the timer "sweep-carts" fires once for each period

  Scenario: a recurring timer set again with the same handler and period keeps its next due time
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "1 minute"
    When a handler of "catalog" sets the recurring timer "sweep-carts" for "sweep" with a period of "1 minute" again
    Then the next due time of "sweep-carts" is the one it had before it was set again

  Scenario: a recurring timer set again is given the new value the next time it fires
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds" and the value "aisle-7"
    When a handler of "catalog" sets the recurring timer "sweep-carts" for "sweep" with a period of "2 seconds" and the value "aisle-9"
    Then the next due time of "sweep-carts" is the one it had before it was set again
    And the handler "sweep" is given "aisle-9" the next time the timer "sweep-carts" fires

  Scenario Outline: a recurring timer set again with another handler or another period is replaced
    Given a handler "sweep-deep" of "cleanup"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "1 minute"
    When a handler of "catalog" sets the recurring timer "sweep-carts" for "<handler>" with a period of "<period>"
    Then the next due time of "sweep-carts" is the one the handler that set it again gave it
    And the timer "sweep-carts" runs "<handler>" once for each period of "<period>"

    Examples:
      | handler    | period    |
      | sweep      | 2 seconds |
      | sweep-deep | 1 minute  |

  Scenario Outline: a period of zero or less is refused
    When a handler of "catalog" sets the recurring timer "sweep-carts" for "sweep" with a period of "<period>"
    Then the handler is refused
    And the refusal names the timer "sweep-carts"
    And "catalog" has no timer "sweep-carts"

    Examples:
      | period    |
      | 0 seconds |
      | -1 second |

  Scenario: a recurring timer set by the handler of another has a period of its own
    Given a handler "sweep-all" of "cleanup" that, the first time it runs, sets the recurring timer "sweep-old" for "sweep" with a period of "3 seconds"
    And the recurring timer "sweep-carts" set for "sweep-all" with a period of "2 seconds"
    When the timers "sweep-carts" and "sweep-old" have each fired 3 times
    Then each due time "sweep-carts" fired for is "2 seconds" after the due time before it
    And each due time "sweep-old" fired for is "3 seconds" after the due time before it

  Scenario: a recurring timer gives its handler as large a value as any timer may, each time it fires
    Given the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds" and a value as large as the limit for a timer
    When the timer "sweep-carts" has fired 2 times
    Then the handler "sweep" was given that value each time
