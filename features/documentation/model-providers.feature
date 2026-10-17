Feature: The documentation of model providers
  A developer learns from the documentation every way a service is given a model provider, which is
  the default model when a service has more than one, what each setting does on each model protocol
  and each preset, every preset and what it sets, how to get a model running on their own machine
  answering an agent, every variable of the model provider family, and what a model provider at an
  address of the developer's own does not give.

  Scenario: the documentation lists every way a service is given a model provider and what each setting does on each
    Given the published documentation
    When a reader reads about choosing a model
    Then the documentation describes how a service is given the model providers "anthropic" and "openai", a model provider from a model protocol, an address, a model and a key, and a model provider from a preset, in each language
    And the documentation says which is the default model when a service is given more than one
    And the documentation says, for each model protocol and each preset, what the effort, the thinking, the temperature and the most tokens settings do and which are ignored

  Scenario: the documentation lists every preset and what it sets
    Given the published documentation
    When a reader reads about presets
    Then the documentation lists every preset the platform names
    And the documentation says, for each preset, its model protocol, its address, whether it needs a key, its default model and what it does not accept

  Scenario: the documentation gets a model on the developer's own machine answering an agent
    Given the published documentation
    When a reader reads about running a model on their own machine
    Then the documentation describes configuring a model provider at the address of a model running on the developer's machine, in each language
    And the documentation names models known to call tools

  Scenario: the documentation lists every variable of the model provider family and says each is a platform setting
    Given the published documentation
    When a reader looks up the variables of a service
    Then the documentation lists every variable that configures a model provider
    And the documentation says that every such variable, and every variable of the model providers "anthropic" and "openai", is a platform setting

  Scenario: the documentation's limitations say what a model provider at an address of the developer's own does not give
    Given the published documentation
    When a reader looks up model providers in the limitations of the documentation
    Then the documentation says that such a model provider gives no refusal reason, no cache accounting where its address reports none, tool calls that are the model's and its template's to get right, and no provider state across an interrupted turn
