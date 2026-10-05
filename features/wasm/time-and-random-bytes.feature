Feature: A module reading the time and asking for random bytes
  A module has no clock and nothing random of its own. It asks the platform's own program for the
  time and for random bytes, from any handler, and neither makes it wait.

  Background:
    Given a module "rewards"

  Scenario: the time a module's step is told is the platform's, read while the step runs
    Given a workflow of "rewards" whose step "expire" reads the time
    When the workflow runs
    Then the time the step is told is between when the platform started the step and when the step ended

  Scenario Outline: a module reads the time from every handler
    Given <handler> of "rewards" that reads the time
    When the platform runs it
    Then it is told the platform's time

    Examples:
      | handler                  |
      | a command of an entity   |
      | a command of a workflow  |
      | a step of a workflow     |
      | a consumer               |
      | a timed action           |
      | a route of an endpoint   |

  Scenario: a module is given random bytes that differ each time it asks
    Given an entity of "rewards" whose command "open" asks twice for "16" random bytes
    When the command "open" is sent to the entity
    Then the command is given "16" random bytes each time
    And what it was given the first time and the second time differ

  Scenario: a module built before a module could ask for the time still reads the time
    Given a module "older" built with an SDK from before a module could ask the platform for the time
    When a handler of "older" reads the time
    Then the handler is told the platform's time
