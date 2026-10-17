Feature: The documentation of model providers
  A developer learns from the documentation which model providers the platform offers, how a
  service is given each, which is the default model when a service has more than one, what each
  setting does on each model provider, and what the model provider openai does not do.

  Scenario: the documentation lists both model providers and what each setting does on each
    Given the published documentation
    When a reader reads about choosing a model
    Then the documentation describes how a service is given the model provider "anthropic" and the model provider "openai"
    And the documentation says which is the default model when a service is given both
    And the documentation says, for each model provider, what each setting does and which settings are ignored

  Scenario: the documentation lists every variable of the model provider openai
    Given the published documentation
    When a reader looks up the variables of a service
    Then the documentation lists every variable the model provider "openai" reads
    And the documentation says that every variable of the model provider "openai" is a platform setting

  Scenario: the documentation's limitations say what the model provider openai does not do
    Given the published documentation
    When a reader looks up model providers in the limitations of the documentation
    Then the documentation says what the model provider "openai" does not do
