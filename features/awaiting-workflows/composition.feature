Feature: A workflow step waits for another workflow
  A step may start another workflow and wait for its end within the step's own timeout, and
  choose its next step from the state the other ended with. A step whose timeout passes first
  fails as timed out, and the other workflow runs on.

  Background:
    Given a service "accounts" with a workflow "kyc" of the steps "documents" and "decision"
    And a workflow "onboarding" whose step "verify" starts the workflow "kyc" and waits for its end within the step's timeout

  Scenario: a step goes on with the state the other workflow ended with
    When the workflow "a1" of "onboarding" runs the step "verify"
    Then "onboarding" moves to the step its handler chooses from the state "kyc" ended with

  Scenario: a step whose timeout passes before the other workflow ends fails as timed out
    Given the step "verify" of "onboarding" has a timeout of "2 seconds"
    And the step "decision" of "kyc" takes "20 seconds"
    When the workflow "a2" of "onboarding" runs the step "verify"
    Then the step "verify" fails as timed out
    And the workflow "kyc" started for "a2" runs on to its end
