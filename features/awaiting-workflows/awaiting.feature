Feature: Waiting for a workflow's end
  A caller waits for a workflow's end by its id, within a time the caller gives, and is answered
  once the workflow has completed or failed, at once when it already had. A caller may send a
  command and wait for the end as one call. A completed workflow answers the state it ended with;
  a failed one answers a failure naming the step and the reason, which is not a refusal of the
  call; a deleted one answers a failure that says so. A paused workflow has not ended. A wait not
  answered in time is told it timed out, and the workflow runs on.

  Background:
    Given a service "pricing" with a workflow "quote" of the steps "rates", "margin" and "offer"

  Scenario: a caller sends a command and is answered with the state the workflow ended with
    When a caller sends the command "start" to the workflow "q1" of "quote" and waits for its end within "30 seconds"
    Then the caller is answered with the state "q1" ended with
    And the caller is answered after the step "offer" has run

  Scenario: a caller that waits for a workflow that fails is answered with the failure, not a refusal
    Given the step "margin" of "quote" fails after its retries
    When a caller sends the command "start" to the workflow "q2" of "quote" and waits for its end within "30 seconds"
    Then the caller is answered with a failure that names the step "margin" and the reason
    And the caller is not answered with a refusal of the command

  Scenario: a command that is refused is answered at once and no wait begins
    Given the command "start" of "quote" refuses a bad request
    When a caller sends the command "start" to the workflow "q3" of "quote" with a bad request and waits for its end
    Then the caller is answered with the refusal at once
    And no wait begins

  Scenario: a caller is answered when the workflow ends and not by asking again and again
    Given a caller waiting for the end of the workflow "q1" of "quote"
    When "q1" ends
    Then the caller is answered within "1 second" of the end being recorded
    And the standing of "q1" was not asked for again and again while the caller waited

  Scenario: a workflow that has ended answers a wait at once
    Given the workflow "q1" of "quote" has ended as completed
    When a caller waits for the end of "q1" within "10 seconds"
    Then the caller is answered with the state "q1" ended with at once

  Scenario: a wait not answered in time is told it timed out and the workflow runs on
    Given the workflow "q4" of "quote" is running a step that takes "20 seconds"
    When a caller waits for the end of "q4" within "2 seconds"
    Then the caller is told the wait timed out
    And "q4" runs on to its end

  Scenario: a caller whose wait timed out waits again and is answered
    Given a caller that was told its wait for the workflow "q4" of "quote" timed out
    When the caller waits for the end of "q4" again within "30 seconds"
    Then the caller is answered with the state "q4" ended with

  Scenario: a paused workflow has not ended
    Given the workflow "q5" of "quote" is paused after its step "rates"
    When a caller waits for the end of "q5" within "2 seconds"
    Then the caller is told the wait timed out
    And "q5" is still paused

  Scenario: a workflow that has recorded nothing is waited for until the wait times out
    Given the workflow "q6" of "quote" has recorded nothing
    When a caller waits for the end of "q6" within "2 seconds"
    Then the caller is told the wait timed out

  Scenario: a workflow deleted while it is waited for answers a failure that says so
    Given a caller waiting for the end of the workflow "q7" of "quote"
    When "q7" is deleted
    Then the caller is answered with a failure that says the workflow was deleted

  Scenario: two callers waiting for one workflow are each answered
    Given two callers waiting for the end of the workflow "q1" of "quote"
    When "q1" ends
    Then each caller is answered with the state "q1" ended with

  Scenario: a wait is one call in the topology
    Given a caller that waited for the end of the workflow "q1" of "quote" and was answered
    When a developer reads the service's topology
    Then the topology shows one observed call from the caller to "quote", handled as ok
