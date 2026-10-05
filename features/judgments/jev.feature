Feature: A service pointed at the Jev judgment provider
  The platform ships one judgment provider, TypeSafe AI's Jev. A service is given the provider's
  credential in its environment and names the model version its thresholds were chosen against;
  answers are mapped back onto the questions that asked them, and checked against them.

  Background:
    Given a service whose judgment provider is "Jev"

  Scenario: one request carries the model version, the judged content and every question
    Given the judgment provider's credential in the service's environment
    And a choice question with described options, a score question with levels in order and a yes or no question with its instructions
    When a judgment is asked for
    Then one request is sent to the judgment provider holding the model version, the judged content and the three questions
    And each answer is given back as the answer to the question that asked it

  Scenario Outline: the model version sent is a fixed one unless the developer named another
    Given the service <named>
    When a judgment is asked for
    Then the request names <sent>

    Examples:
      | named                                         | sent                                  |
      | names no model version                        | a fixed model version, never an alias |
      | names the alias "latest" as its model version | the alias "latest"                    |

  Scenario: a judgment tells its reader which model version answered it
    Given the service names the alias "latest" as its model version
    When a judgment is answered
    Then the judgment says which model version the judgment provider reported, which the alias stood for

  Scenario: a service sends its judgments to the address it was given
    Given the service is given the address "https://models.example.test" for the judgment provider
    When a judgment is asked for
    Then the request is sent to "https://models.example.test" in the same form

  Scenario Outline: a busy judgment provider is asked again until the judgment's time limit
    Given the judgment provider answers that <busy>, and says when to ask again
    When a judgment is asked for
    Then the judgment is asked for again after the time the judgment provider said
    And the judgment fails once asking again would pass the judgment's time limit

    Examples:
      | busy                              |
      | the service is over its rate limit |
      | it is overloaded                   |

  Scenario Outline: a judgment the judgment provider refuses fails at once with its message
    Given the judgment provider refuses <refused>
    When a judgment is asked for
    Then the judgment fails without being asked for again
    And the failure carries the judgment provider's message

    Examples:
      | refused                  |
      | the credential           |
      | the request as not valid |

  Scenario Outline: an answer that does not fit its question fails the judgment
    Given the judgment provider answers <answer>
    When the judgment is read from the answer
    Then the judgment fails naming the question
    And no answer is invented

    Examples:
      | answer                                            |
      | with no answer to one question asked              |
      | with an option the question did not offer         |
      | with an answer to a question of another kind      |

  Scenario: a judgment the judgment provider does not answer in time fails naming the provider
    Given the judgment provider does not answer within the judgment's time limit
    When a judgment is asked for
    Then the judgment fails, saying the time limit passed and naming the judgment provider

  Scenario: the credential is never shown in a failure
    Given a judgment that fails for any reason
    When the failure is read or logged
    Then the judgment provider's credential is in neither

  Scenario: a service with no credential in its environment fails as it starts
    Given the service's environment has no credential for the judgment provider
    When the service starts
    Then the service fails to start, naming the missing variable
