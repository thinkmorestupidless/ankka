Feature: A model provider at any address
  Every model provider is one of two model protocols pointed somewhere: a vendor's own address, or
  a model running on the developer's own machine, or a server of their own in a cluster. A service
  configures one by a name of its choosing from a model protocol, an address, a model and a key or
  none, or from a preset the platform has tested and gives what the preset leaves open. The name
  is the service's, in logs, failures and model usage.

  Scenario: a model provider is configured by name from a model protocol, an address, a model and no key
    Given a service "support" written in "Python" whose descriptor configures the model provider "local" with the model protocol "openai", the address "http://localhost:11434/v1", the model "qwen3:8b" and no key
    And an agent "helper" of "support" that names the model provider "local"
    When a caller sends a message to "helper"
    Then the message is answered by the model provider "local"
    And the call was made in the model protocol "openai" to the address "http://localhost:11434/v1" for the model "qwen3:8b", with no key

  Scenario: a model provider at an address of the developer's own answers an agent with tool calls
    Given a service "support" with the model provider "local" at an address of the developer's own
    And an agent "helper" of "support" that names the model provider "local", with a tool "lookup"
    When a caller sends a message to "helper" that makes the model call "lookup"
    Then the tool "lookup" runs with the arguments the model gave
    And the model is sent the result of "lookup"
    And the caller is answered

  Scenario: the model provider's name is the service's, in logs, failures and model usage
    Given a service "support" with the model provider "fast" configured from the preset "groq"
    And an agent "helper" of "support" that names the model provider "fast"
    When a caller sends a message to "helper" and the model provider "fast" cannot be reached
    Then the caller is told the model provider "fast" is unavailable
    And the logs of "support" name the model provider "fast" and not "groq"
    And when the model provider "fast" answers, the model usage of the turn is recorded under "fast"

  Scenario: a Scala service builds a model provider from the same four values
    Given a service "support" written in "Scala" that builds the model provider "local" from the model protocol "openai", the address "http://localhost:11434/v1", the model "qwen3:8b" and no key
    And an agent "helper" of "support" that names the model provider "local"
    When a caller sends a message to "helper"
    Then the message is answered by the model provider "local"
    And the model provider reports its name as "local"

  Scenario Outline: a model provider configured with an address and no model protocol is refused at the service's start
    Given a descriptor for a service "support" written in "Python" that configures the model provider "local" with <given> and no preset
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" names the model provider "local" and says that <missing> is missing

    Examples:
      | given                                                   | missing          |
      | an address and a model, and no model protocol           | a model protocol |
      | a model protocol and an address, and no model           | a model          |
      | a model protocol and a model, and no address            | an address       |

  Scenario Outline: a model provider configured with a reserved name is refused at the service's start
    Given a descriptor for a service "support" written in "Python" that configures a model provider named "<name>"
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" says that "<name>" is reserved or is not a name a model provider may have

    Examples:
      | name     |
      | scripted |
      | default  |
      | name     |
      | script   |
      | Fast       |
      | my_model   |
      | local-fast |
      | 2fast      |

  Scenario Outline: a model provider whose settings do not agree is refused at the service's start
    Given a descriptor for a service "support" written in "Python" that configures the model provider "local" with <setting>
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" names the model provider "local" and the setting

    Examples:
      | setting                                         |
      | the model protocol "grpc"                       |
      | the address "not a url"                         |
      | the preset "no-such-preset"                     |
      | a default model "fast" that is not configured   |

  Scenario: a variable of the model provider family that is not a setting is refused at the service's start
    Given a descriptor for a service "support" written in "Python" that gives the variable "ANKKA_MODEL_FAST_URLS"
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" names the variable "ANKKA_MODEL_FAST_URLS" and says it is not a setting of a model provider

  Scenario: a tool call's id is rewritten for a model provider that constrains it and the session keeps the model's
    Given a session "s-1" holding a tool call of "lookup" with the id "toolu_01ABC" made by the model provider "anthropic" and its result
    And a model provider "eu" configured from the preset "mistral", which constrains the ids of tool calls
    When an agent that names the model provider "eu" takes a turn in the session "s-1"
    Then the model provider "eu" is sent the tool call of "lookup" with an id of the shape it constrains, followed by its result under the same id
    And the session "s-1" still holds the tool call of "lookup" with the id "toolu_01ABC"

  Scenario: a preset configures a model provider from a name and a key
    Given a service "support" written in "Python" whose descriptor configures the model provider "eu" with the preset "mistral" and a key
    And an agent "helper" of "support" that names the model provider "eu"
    When a caller sends a message to "helper"
    Then the call was made in the model protocol and to the address the preset "mistral" names, for its default model

  Scenario: a preset that needs a key is refused at the service's start without one
    Given a descriptor for a service "support" written in "Python" that configures the model provider "eu" with the preset "mistral" and no key
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" names the model provider "eu" and the variable that gives its key

  Scenario: a value given beside a preset overrides what the preset set
    Given a service "support" written in "Python" whose descriptor configures the model provider "eu" with the preset "mistral", a key and the model "mistral-small-latest"
    And an agent "helper" of "support" that names the model provider "eu"
    When a caller sends a message to "helper"
    Then the call was made to the address the preset "mistral" names, for the model "mistral-small-latest"

  Scenario: a preset without a key is a model provider at an address that needs none
    Given a service "support" written in "Python" whose descriptor configures the model provider "local" with the preset "ollama" and the model "qwen3:8b"
    And an agent "helper" of "support" that names the model provider "local"
    When a caller sends a message to "helper"
    Then the call was made to the address the preset "ollama" names, with no key

  Scenario: a value of the model provider family beside a vendor's key overrides what the key set
    Given a service "support" written in "Python" whose descriptor gives the key of the model provider "openai" alone, and the address "http://proxy.internal/v1" and the model "gpt-5-mini" for the model provider "openai"
    And an agent "triage" of "support" that names the model provider "openai"
    When a caller sends a message to "triage"
    Then the call was made in the model protocol "openai" to the address "http://proxy.internal/v1" for the model "gpt-5-mini", with the key

  Scenario: the model of the model provider anthropic given twice with different values is refused at the service's start
    Given a descriptor for a service "support" written in "Python" that gives the key of the model provider "anthropic" and names its model twice, as "claude-opus-5" and as "claude-sonnet-5"
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" names the model provider "anthropic" and both models

  Scenario Outline: every preset the platform names is configured from its name
    Given a service "support" written in "Python" whose descriptor configures the model provider "p" with the preset "<preset>", a key where the preset needs one and a model where it names none
    When "support" starts
    Then the model provider "p" is configured in the model protocol and at the address the preset "<preset>" names

    Examples:
      | preset              |
      | openai              |
      | anthropic           |
      | ollama              |
      | lmstudio            |
      | llamacpp            |
      | docker-model-runner |
      | vllm                |
      | groq                |
      | mistral             |
      | deepseek            |
      | xai                 |
      | gemini              |
      | openrouter          |
      | huggingface         |
      | bedrock             |
      | azure               |

  Scenario Outline: a setting a model provider's address does not accept is withheld, never a failed call
    Given a service "support" with the model provider "local" at an address of the developer's own
    And an agent "helper" of "support" that names the model provider "local" and asks for <setting>
    When a caller sends a message to "helper"
    Then the call is made with <sent>
    And the caller is answered

    Examples:
      | setting                                   | sent                                              |
      | an effort above what the model offers     | the highest effort the model offers               |
      | thinking off, on a model that cannot think | no effort at all                                  |
      | a temperature                             | the temperature                                   |

  Scenario: a model provider whose address reports no model usage is recorded as none
    Given a service "support" with the model provider "local" at an address of the developer's own that reports no model usage
    And an agent "helper" of "support" that names the model provider "local"
    When a caller sends a message to "helper"
    Then the caller is answered
    And the model usage of the turn is recorded as nothing, under "local"
