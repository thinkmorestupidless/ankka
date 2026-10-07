Feature: Recurring timers in every language
  A service sets a recurring timer the same way whether it is written in Scala, Python,
  TypeScript or Rust, because the platform's own program keeps the timer and gives it each next
  due time, and the developer's program only sets it.

  Scenario Outline: a recurring timer fires once for each period in every language
    Given a service "catalog" written in "<language>" with a timed action "cleanup"
    And a handler "sweep" of "cleanup"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" has fired 3 times
    Then each due time it fired for is one period after the due time before it

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a recurring timer set again keeps its next due time in every language
    Given a service "catalog" written in "<language>" with a timed action "cleanup"
    And a handler "sweep" of "cleanup"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "1 minute"
    When a handler of "catalog" sets the recurring timer "sweep-carts" for "sweep" with a period of "1 minute" again
    Then the next due time of "sweep-carts" is the one it had before it was set again

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a cancelled recurring timer does not fire again in every language
    Given a service "catalog" written in "<language>" with a timed action "cleanup"
    And a handler "sweep" of "cleanup"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When a handler of "catalog" cancels the timer "sweep-carts"
    Then "catalog" has no timer "sweep-carts"
    And the timer "sweep-carts" does not fire again

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a handler is told the due time of the timer that ran it in every language
    Given a service "catalog" written in "<language>" with a timed action "cleanup"
    And a handler "sweep" of "cleanup"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" has fired 2 times
    Then the handler "sweep" was told 2 due times
    And the second due time it was told is one period after the first

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
