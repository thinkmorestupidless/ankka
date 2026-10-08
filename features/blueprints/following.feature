Feature: Following runs
  A tool called during a run is told which run it serves, so what it writes can say so. A service's
  consumers subscribe to its blueprint versions and runs as to any entity's changes, so another
  component, or another system, keeps its own record of every run.

  Background:
    Given a service with a scripted model
    And the blueprint "brief" held at blueprint version 1, with the steps "outline" and "draft"

  Scenario: a tool called in a run is told the run, the step and the version
    Given a worker in "draft" with a tool that records what it is told
    When the worker calls the tool in a run of "brief" under the run id "weekly-41"
    Then the tool was told the run id "weekly-41", the step "draft" and blueprint version 1 of "brief"

  Scenario: a tool called outside a run is told it is in none
    Given a request agent with a tool that records what it is told
    When the model calls the tool in answer to a message
    Then the tool was told it is in no run

  Scenario: a consumer subscribes to a service's runs
    Given a consumer subscribed to the service's runs
    When a run of "brief" is started
    Then the consumer is told the run has started, with its blueprint version and its input

  Scenario: a consumer subscribes to a service's blueprint versions
    Given a consumer subscribed to the service's blueprint versions
    When the service registers blueprint version 2 of "brief"
    Then the consumer is given blueprint version 2 of "brief"

  Scenario: a consumer is told of each step's end and of the run's end, in order
    Given a consumer subscribed to the service's runs
    When a run of "brief" is completed
    Then the consumer is told the step "outline" ended, then the step "draft" ended, then the run ended, each with its result
