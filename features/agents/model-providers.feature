Feature: Two model providers in one service
  A service talks to models of more than one model provider. A Scala service hands the platform
  the model providers it built and names one as its default model; a service written in another
  language is given each model provider's key, and the platform's own program holds the keys and
  talks to the models. An agent, an autonomous agent's definition and a blueprint's worker each
  name a model provider, or none and are answered by the default model. A session is the
  platform's, not a model provider's: an agent on one continues what an agent on another began,
  and the session's model usage is both added together.

  Scenario: a Scala service runs one agent on OpenAI and another on Anthropic
    Given a service "support" written in "Scala" with the model providers "anthropic" and "openai", and "anthropic" its default model
    And an agent "triage" of "support" that names the model provider "openai"
    And an agent "drafting" of "support" that names no model provider
    When a caller sends one message to "triage" and one to "drafting"
    Then the message to "triage" is answered by the model provider "openai"
    And the message to "drafting" is answered by the model provider "anthropic"

  Scenario: a handler naming a model provider is answered by that model provider and no other
    Given a service "support" with the model providers "anthropic" and "openai", and "anthropic" its default model
    And an agent "triage" of "support" that names the model provider "openai"
    When a caller sends a message to "triage"
    Then the message is answered by the model provider "openai"
    And the model provider "anthropic" is not asked

  Scenario: an OpenAI model's refusal is the caller's refusal, not an answer
    Given an agent "triage" that names the model provider "openai"
    When the model declines the message in the session "s-1" with the reason "outside policy"
    Then the caller of "triage" is refused, with the reason "outside policy"
    And the session "s-1" holds no answer for the turn

  Scenario Outline: an agent naming the model provider openai is answered by it in every language
    Given a service "support" written in "<language>" whose descriptor gives the keys of the model providers "anthropic" and "openai", and names "anthropic" its default model
    And an agent "triage" of "support" that names the model provider "openai"
    When a caller sends a message to "triage"
    Then the message is answered by the model provider "openai"
    And the process of "support" is not given the key of either model provider

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario: a service written in another language with two model providers and no default model refuses only an agent that names none
    Given a service "support" written in "Python" whose descriptor gives the keys of the model providers "anthropic" and "openai" and names no default model
    And an agent "triage" of "support" that names the model provider "openai"
    And an agent "drafting" of "support" that names no model provider
    When a caller sends one message to "triage" and one to "drafting"
    Then the message to "triage" is answered by the model provider "openai"
    And the caller of "drafting" is refused
    And the refusal names the variable "ANKKA_MODEL_DEFAULT" and the model providers "anthropic" and "openai"

  Scenario Outline: a service written in another language with one model provider has it as its default model
    Given a service "support" written in "Python" whose descriptor gives the key of the model provider "<provider>" alone
    And an agent "drafting" of "support" that names no model provider
    When a caller sends a message to "drafting"
    Then the message is answered by the model provider "<provider>"

    Examples:
      | provider  |
      | anthropic |
      | openai    |

  Scenario: an autonomous agent of a service written in another language names the model provider openai
    Given a service "ops" written in "Python" whose descriptor gives the keys of the model providers "anthropic" and "openai", and names "anthropic" its default model
    And an autonomous agent "operator" of "ops" whose definition names the model provider "openai"
    When a caller assigns a task to "operator"
    Then every iteration of the task is a call to the model provider "openai"

  Scenario: an agent on one model provider continues a session another model provider began
    Given an agent "drafting" that names the model provider "anthropic" and an agent "triage" that names the model provider "openai"
    And a turn of "drafting" in the session "s-1", in which the model called the tool "lookup" and was told its result
    When a caller sends a message to "triage" in the session "s-1"
    Then the model provider "openai" is sent the conversation of "s-1" whole
    And the conversation it is sent holds the tool call of "lookup" and its result

  Scenario: a tool call one model provider made is sent to the other with its result
    Given a session "s-1" holding a tool call of "lookup" made by the model provider "anthropic" and its result
    When an agent that names the model provider "openai" takes a turn in the session "s-1"
    Then the model provider "openai" is sent the tool call of "lookup" with the arguments the model gave
    And the tool call is followed by its result

  Scenario: a session's model usage is both model providers' added together
    Given a turn of an agent on the model provider "anthropic" in the session "s-1"
    And a turn of an agent on the model provider "openai" in the session "s-1"
    When a reader reads the model usage of the session "s-1"
    Then the model usage is the two turns' model usage added together

  Scenario: a blueprint runs one worker on each model provider
    Given a service with the model providers "anthropic" and "openai", and "anthropic" its default model
    And the blueprint "brief" with the worker "research" that names the model provider "openai" and the worker "write" that names none
    When a caller starts a run of "brief"
    Then every call the worker "research" makes is to the model provider "openai"
    And every call the worker "write" makes is to the model provider "anthropic"

  Scenario: an autonomous agent's definition names the model provider openai
    Given a service written in "Scala" with the model providers "anthropic" and "openai", and "anthropic" its default model
    And an autonomous agent "operator" whose definition names the model provider "openai"
    When a caller assigns a task to "operator"
    Then every iteration of the task is a call to the model provider "openai"
