Feature: Checking a blueprint before it is held
  A blueprint is checked whole before it is held, against the service that will run it. A blueprint
  with problems is refused with every problem named in one answer, and nothing is held.

  Background:
    Given a service with the tools "search" and "keep entry", the model "default" and the judgment question "names a paper"

  Scenario: a blueprint is refused with every problem named in one answer
    When the service registers a blueprint with four problems
    Then the service is refused, with all four problems named
    And no blueprint version is held

  Scenario: a worker naming a tool the service does not have is refused
    When the service registers a blueprint with a worker whose tools include "translate"
    Then the service is refused, naming the worker and the tool "translate"

  Scenario: a worker naming a model the service does not have is refused
    When the service registers a blueprint with a worker whose model is "large"
    Then the service is refused, naming the worker and the model "large"

  Scenario: a step naming a judgment question the service does not have is refused
    When the service registers a blueprint with a judge step asking the judgment question "funny"
    Then the service is refused, naming the step and the judgment question "funny"

  Scenario: a step reading a step that comes after it is refused
    When the service registers a blueprint whose first step reads the result of its second step
    Then the service is refused, naming both steps

  Scenario: a step naming a pattern the platform does not have is refused
    When the service registers a blueprint with a step whose pattern is "vote"
    Then the service is refused, naming the step and the pattern "vote"

  Scenario: a step whose pattern needs a list and reads something else is refused
    When the service registers a blueprint whose for-each step reads a step whose result is not a list
    Then the service is refused, naming the for-each step and the step it reads

  Scenario: a worker with no budget is refused
    When the service registers a blueprint with a worker that has no budget
    Then the service is refused, naming the worker

  Scenario: a blueprint with a schedule is refused in a service without timers
    Given a service without timers
    When the service registers a blueprint with a schedule
    Then the service is refused, naming the schedule and the missing timers

  Scenario: a worker no step uses is noted and the blueprint is held
    When the service registers a blueprint with a worker that no step uses
    Then the blueprint is held
    And the service is told that no step uses the worker
