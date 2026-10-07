Feature: The documentation of timers
  A developer who has something to run every hour or every day learns from the documentation that
  it is a recurring timer, how its due times are kept one period apart, and what a period cannot
  say.

  Scenario: the documentation describes a recurring timer
    Given the published documentation
    When a reader reads about timers
    Then the documentation describes a recurring timer and its period
    And the documentation says that each next due time is one period after the due time before it
    And the documentation says that a recurring timer fires until it is cancelled or replaced
    And the documentation says that a service may set its recurring timers each time it starts
    And the documentation says that a handler is told the due time of the timer that ran it
    And the documentation says that a recurring timer fires once, and not once for each period, when several of its due times have passed

  Scenario: the documentation does not tell a handler to set its own timer again
    Given the published documentation
    When a reader reads about a timer whose handler sets it again
    Then the documentation says that a call to be made again and again is a recurring timer
    And the documentation does not say that a handler sets the timer that ran it again for that

  Scenario: the documentation says what a recurring timer does after a failure
    Given the published documentation
    When a reader reads about a timer whose handler fails
    Then the documentation says that a recurring timer whose handler fails fires again after its backoff
    And the documentation says that its next due time is then taken from the due time it failed for

  Scenario: the documentation says what a period cannot say
    Given the published documentation
    When a reader reads about what a recurring timer does not do
    Then the documentation says that a period is a length of time, and never a time of day or a day of the week
