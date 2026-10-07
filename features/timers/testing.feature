Feature: Testing a recurring timer
  A test starts a whole service with the test kit and reads the due times a recurring timer fired
  for, so a test of a period reads what the platform recorded and not how long its own machine
  took.

  Scenario: a test reads the due times a recurring timer fired for
    Given a test that starts the service "catalog" with the test kit
    And the service "catalog" has a timed action "cleanup" with a handler "sweep"
    And the recurring timer "sweep-carts" set for "sweep" with a period of "2 seconds"
    When the timer "sweep-carts" has fired 3 times
    Then the test reads 3 due times for the timer "sweep-carts"
    And each due time is one period after the due time before it
