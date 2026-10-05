Feature: Testing judgments with a scripted judgment provider
  A test gives its service a scripted judgment provider, with no credential and no network. It queues
  the answers the next judgments are given, or a standing answer for a question asked every time, and
  reads back every request the provider was sent. A script that has no answer fails the test; it
  never gives a default.

  Background:
    Given a test of a service with a scripted judgment provider

  Scenario: queued answers are given in order and every request is kept for the test
    Given answers queued for two judgments
    When two judgments are asked for
    Then the first is given the first queued answers and the second the second
    And the test can read each request the scripted judgment provider was sent, with its judged content and its questions

  Scenario: a script with no answer for a question fails naming the question
    Given no answer queued and no standing answer for the question "urgent"
    When a judgment asking "urgent" is asked for
    Then the judgment fails naming the question "urgent"
    And the judgment is not asked for again

  Scenario Outline: an answer that does not fit its question fails the test naming what does not fit
    Given an answer queued with <mismatch>
    When the answer is queued or given
    Then the test fails naming <named>

    Examples:
      | mismatch                                      | named                     |
      | an answer to a question the request does not ask | the question            |
      | an option the question does not offer         | the option                |
      | a place outside the question's levels         | the place and the levels  |
      | a probability of 1.5                          | the probability           |

  Scenario: a standing answer is given to every judgment that asks its question
    Given a standing answer for the question "overrides-instructions"
    And one answer queued for the question "team"
    When 3 judgments asking "overrides-instructions" are asked for
    Then each is given the standing answer
    And the answer queued for "team" is still queued

  Scenario: an agent's judgments and its model calls draw on their own scripts
    Given an agent with a judged guardrail, a scripted judgment provider and a scripted model
    When a request reaches the agent
    Then the guardrail's judgment is answered from the judgment script alone
    And the agent's turn is answered from the model script alone

  Scenario Outline: an answer scripted in part reads as a whole answer that agrees with it
    Given an answer queued with only <scripted>
    When the answer is read
    Then <consistent>

    Examples:
      | scripted                                  | consistent                                                                 |
      | the chosen option "billing"               | "billing" has the highest probability and the probabilities agree with the confidence |
      | the probability of yes 0.8                | the probability of yes is 0.8                                              |
