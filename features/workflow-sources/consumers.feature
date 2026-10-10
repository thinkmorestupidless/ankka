Feature: A consumer reads a workflow
  A consumer may read a workflow as it reads an entity. It is handed each state the workflow
  recorded, at least once and in order, with the standing once the effect that recorded it is
  applied, the workflow's id as its subject and the record's sequence number. A consumer that
  reacts to a workflow's end reads the standing and ignores the changes before it; a failure a
  step does not record in the state is no change.

  Background:
    Given a service "money" with a workflow "transfer" of the steps "withdraw" and "deposit"
    And an event sourced entity "ledger"

  Scenario: a consumer publishes once when a workflow ends as completed
    Given a consumer "settlement" that reads the workflow "transfer" and publishes to the topic "transfers-settled" for each change whose standing is completed
    When the workflow "t1" of "transfer" runs from its start to its end
    Then one message about "t1" is published to "transfers-settled"
    And no message is published for the changes of "t1" before its end

  Scenario: a consumer calls an entity once when a workflow records its failure
    Given a consumer "settlement" that reads the workflow "transfer" and calls "ledger" for each change whose standing is failed
    And the step "deposit" of "transfer" fails after its retries and fails over to "refund", which records the failure in the state and fails the workflow
    When the workflow "t2" of "transfer" runs from its start
    Then "ledger" is called once about "t2"

  Scenario: a consumer is handed one change for a state recorded with a transition, stamped with the step moved to
    Given a consumer "settlement" that reads the workflow "transfer"
    When the step "withdraw" of the workflow "t1" of "transfer" records its state and moves to "deposit"
    Then "settlement" is handed one change for it
    And the change carries the state and the standing running on "deposit"
    And the change carries the sequence number of the state's record and "t1" as its subject

  Scenario: a deleted workflow runs the consumer's deletion handler
    Given a consumer "settlement" that reads the workflow "transfer" and has read every change of "t1"
    When the workflow "t1" of "transfer" is deleted
    Then the deletion handler of "settlement" runs for "t1"

  Scenario: a workflow that times out without recording its state delivers no change
    Given a consumer "settlement" that reads the workflow "transfer"
    And "transfer" declares a timeout of "2 seconds" for the whole workflow
    When the workflow "t3" of "transfer" runs a step that takes longer than "2 seconds"
    Then "settlement" is handed no change for the timeout
