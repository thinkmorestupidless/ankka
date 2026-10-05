Feature: The documentation of judgments
  A developer, or a model that retrieved one page alone, learns from the documentation what a
  judgment is, when to ask for one instead of the model, and what a judgment provider gets wrong.

  Scenario: the documentation guides building with judgments from tested code
    Given the published documentation
    When a developer reads about judgments
    Then the documentation guides building with judgments, and every sample in the guide is included from tested code
    And the documentation of agents says what a judgment is and how it differs from talking to the model

  Scenario: the documentation says what a judged guardrail can and cannot be trusted with
    Given the published documentation
    When a developer reads about judged guardrails
    Then the documentation says that a judged guardrail reads text its author may have written to defeat it
    And the documentation says that a judged guardrail stands beside guardrails that are not judged rather than replacing them
    And the documentation says what happens when the judgment provider cannot be reached

  Scenario: the documentation says why the model version is fixed
    Given the published documentation
    When a developer reads about choosing thresholds
    Then the documentation says that answers can change between model versions
    And the documentation says that the service therefore names one, and that every judgment says which answered it

  Scenario: the documentation lists every setting of the judgment provider
    Given the published documentation
    When a developer reads the settings of the "Jev" judgment provider
    Then every variable and setting it reads is listed and described

  Scenario: the documentation says where judgments are not available and how they differ from Akka's
    Given the published documentation
    When a developer reads the limitations of judgments
    Then the documentation says that judgments are for the agents of services written in "Scala" alone, and names what is not yet there
    And the documentation of differences from Akka says where judgments differ from Akka's

  Scenario: every page about judgments can be found
    Given the published documentation
    When the documentation is built
    Then every page about judgments is in the documentation's navigation and in at least one skill
