Feature: Runs of a blueprint
  A run is one carrying out of one blueprint version, under an id its caller chooses. The platform
  carries out the steps in order and holds each step's result, and a reader reads the run at any
  time. A run survives a restart with no ended step done again.

  Background:
    Given a service with a scripted model
    And the blueprint "brief" held at blueprint version 1, with the steps "outline", "draft" and "polish"

  Scenario: a run carries out a blueprint's steps in order and holds each step's result
    When a caller starts a run of "brief" with an input of its input shape
    Then the steps "outline", "draft" and "polish" are carried out in that order
    And each step's result is held when the step ends
    And the run is completed

  Scenario: steps that read only what has ended are carried out at once
    Given the blueprint "pair" whose steps "left" and "right" both read only the run's input
    When a run of "pair" is started
    Then the steps "left" and "right" are both in progress at once
    And the run is completed

  Scenario: a run keeps the version that was current when it started
    Given a run of "brief" in progress at blueprint version 1
    When the service registers blueprint version 2 of "brief"
    Then the run carries out its remaining steps from blueprint version 1
    And the run names blueprint version 1 of "brief"

  Scenario: a run started twice under one id is one run
    Given a run of "brief" under the run id "weekly-41"
    When a caller starts a run of "brief" under the run id "weekly-41" again
    Then there is one run under the run id "weekly-41"
    And the caller is given that run

  Scenario: a run id used again for a different blueprint or input is refused
    Given a run of "brief" under the run id "weekly-41"
    When a caller starts a run of "brief" under the run id "weekly-41" with a different input
    Then the caller is refused, naming the run id
    And there is one run under the run id "weekly-41", as it was started

  Scenario: a run whose input does not have the blueprint's input shape is refused
    When a caller starts a run of "brief" with an input that does not have its input shape
    Then the caller is refused, with the reason the input could not be read
    And no run is held

  Scenario: a step is given the run's input and the results it reads
    Given the step "polish" reads the run's input and the result of "draft"
    When a run of "brief" reaches the step "polish"
    Then the worker in "polish" is given the run's input and the result of "draft"
    And the worker in "polish" is not given the result of "outline"

  Scenario: a reader reads a run's progress while it runs
    Given a run of "brief" whose step "outline" has ended and whose step "draft" is in progress
    When a reader reads the run
    Then the reader is shown the result of "outline"
    And the reader is shown that the run is on the step "draft"

  Scenario: a run names the sessions of the workers in each step
    Given a completed run of "brief"
    When a reader reads the run
    Then the reader is shown the session of each worker in each step

  Scenario: a run gives the model usage of each step and of the whole run
    Given a completed run of "brief" whose three steps each made model calls
    When a reader reads the run
    Then the reader is shown the model usage of each step
    And the reader is shown the model usage of the run, which is its steps' usage added together

  Scenario Outline: a caller waits for a run to end
    Given a run of "brief" in progress
    And a caller waiting for the run to end
    When the run is <ending>
    Then the caller is given the run as it ended

    Examples:
      | ending    |
      | completed |
      | failed    |
      | cancelled |

  Scenario: a run resumes after a restart and no ended step is done again
    Given a run of "brief" whose step "outline" has ended and whose step "draft" is in progress
    When the service restarts
    Then the run carries on from the step "draft"
    And the step "outline" is not carried out again

  Scenario: a model call recorded before a restart is not made again
    Given a run of "brief" whose worker in "draft" has had a model call recorded
    When the service restarts
    Then that model call is not made again
    And the run is completed

  Scenario: an ask turn interrupted by a restart runs again from its start
    Given a run of "brief" whose worker in "draft" has made two model calls in a turn that has not ended
    When the service restarts
    Then the turn in "draft" is run again from its start
    And the step "outline" is not carried out again

  Scenario: a step that fails fails the run, naming the step and why
    Given a run of "brief" in progress
    When the worker in "draft" gives up with the reason "nothing to draft from"
    Then the run is failed, naming the step "draft" and the reason "nothing to draft from"
    And the step "polish" is not carried out

  Scenario: a failed run is not tried again by the platform
    Given a failed run of "brief"
    When the service restarts
    Then the run is still failed
    And no step of the run is carried out again

  Scenario: a run that spends its run budget fails, saying so
    Given the blueprint "brief" with a run budget of "4" model calls
    When a run of "brief" makes its fourth model call and has steps left
    Then the run is failed, saying its run budget is spent

  Scenario: a cancelled run finishes the turn in progress and starts nothing after it
    Given a run of "brief" whose worker in "draft" has a turn in progress
    When a caller cancels the run
    Then the turn in progress runs to its end
    And the step "polish" is not carried out
    And the run is cancelled

  Scenario: a step's result that does not have its declared shape is returned to the worker to correct
    Given a run of "brief" whose step "outline" declares a result holding a list of titles
    When the worker in "outline" answers with a result that does not have that shape
    Then the step does not end
    And the worker is told why its result could not be read
    And the worker answers again within its budget

  Scenario: a tool that requires approval makes the run wait for a decision
    Given a worker in "polish" with a tool that requires approval
    When the worker calls the tool in a run of "brief"
    Then the run is waiting for a decision
    And the run shows the approval request
    And the worker's budget is not spent while the run waits

  Scenario: a run that ends while it waits for a decision refuses the approval request
    Given a run of "brief" waiting for a decision on a tool call in "polish"
    When the run passes its time limit
    Then the run is failed, saying its time limit passed
    And the approval request is refused, with a note that the run ended
    And the tool is not run

  Scenario: a reader lists the runs of a blueprint with the version each ran
    Given two runs of "brief" at blueprint version 1 and one at blueprint version 2
    When a reader lists the runs of "brief"
    Then the reader is given three runs, each with its run status and the blueprint version it ran
