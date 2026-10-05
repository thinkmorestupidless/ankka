Feature: Asking typed questions and reading typed answers
  An agent's handler may reply with a judgment: it names the judged content and the questions to ask
  of it, and the judgment provider answers every question at once. A caller reads each answer through
  the question that asked it, typed as the question was declared. A judgment uses no conversation and
  writes nothing to one.

  Background:
    Given an agent "triage" of a service written in "Scala"
    And a choice question "team" over the teams "billing", "shipping" and "accounts"
    And a score question "frustration" over four levels
    And a yes or no question "wants-refund"
    And a handler "triage" of "triage" whose judgment asks "team", "frustration" and "wants-refund" of a ticket

  Scenario: a judgment asks every question of the judged content in one request
    When the handler "triage" is called with a ticket
    Then the judgment provider is sent one request holding the ticket as the judged content and the questions "team", "frustration" and "wants-refund"
    And the caller is given a judgment holding one answer to each question

  Scenario: a choice is read as an option of the type it was declared over
    Given a judgment from the handler "triage"
    When the caller reads the answer to "team"
    Then the caller is given the chosen option as a team
    And a probability for each option
    And a confidence between 0 and 1

  Scenario: a score is read as a place among its levels
    Given a judgment from the handler "triage"
    When the caller reads the answer to "frustration"
    Then the caller is given a place among the levels, which may lie between two of them
    And a probability for each level
    And a confidence between 0 and 1

  Scenario: a yes or no question is read as the probability of yes
    Given a judgment from the handler "triage"
    When the caller reads the answer to "wants-refund"
    Then the caller is given the probability, between 0 and 1, that the answer is yes
    And no confidence

  Scenario: a judgment neither reads nor changes the session's conversation
    Given a session of "triage" that already holds a conversation
    When the handler "triage" is called in that session
    Then none of the conversation is sent to the judgment provider
    And the session's conversation is the same after the call as before it

  Scenario: a handler that replies with a value computed from its judgment gives only that value
    Given a handler "route" of "triage" that replies with a routing decision computed from its judgment
    When the handler "route" is called with a ticket
    Then the caller is given the routing decision
    And the caller is not given the judgment

  Scenario: a judgment with no judgment provider to ask fails saying what to configure
    Given the service has no judgment provider
    When the handler "triage" is called with a ticket
    Then the call fails saying what to configure
    And no judgment provider is asked

  Scenario: a handler that names a judgment provider is answered by it
    Given the service's judgment provider "default"
    And the handler "triage" names the judgment provider "second-opinion"
    When the handler "triage" is called with a ticket
    Then "second-opinion" answers the judgment
    And "default" is not asked

  Scenario: a judgment whose provider fails leaves the conversation as it was
    Given a judgment provider that fails
    When the handler "triage" is called with a ticket
    Then the call fails naming the judgment provider and its failure
    And nothing is added to the session's conversation

  Scenario: an answer read through a question that was not asked is not invented
    Given a judgment from the handler "triage"
    When the caller reads the answer to a question "urgent" that the judgment did not ask
    Then the read fails naming the question "urgent"
