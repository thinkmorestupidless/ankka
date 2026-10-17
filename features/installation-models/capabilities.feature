Feature: What an installation model can do is said at the first call
  Models differ in their capabilities: some call tools, some think, some neither. An agent that
  needs a capability its installation model lacks is told so at its first call, as a wrong vendor
  model name is: the model provider's failure, naming the model and the capability, with the service
  itself unaffected. The documentation names models known to call tools.

  Scenario: an agent with tools on a model that cannot call tools is told so at the first call
    Given an installation whose model server serves the model "tiny:1b", which lacks the capability "tool calling"
    And a deployed service "support" with an agent "helper" on the installation model "tiny:1b"
    And a tool "lookup" of "helper"
    When a caller sends a message to "helper"
    Then the caller is told the model provider is unavailable, because "tiny:1b" lacks the capability "tool calling"

  Scenario: effort on a model that cannot think is told so at the first call
    Given an installation whose model server serves the model "gemma:1b", which lacks the capability "thinking"
    And a deployed service "support" with an agent "helper" on the installation model "gemma:1b" that asks for effort
    When a caller sends a message to "helper"
    Then the caller is told the model provider is unavailable, because "gemma:1b" lacks the capability "thinking"

  Scenario: a capability failure is the model provider's failure and the service is unaffected
    Given a deployed service "support" with an agent "helper" on an installation model that lacks a capability "helper" needs
    And an autonomous agent "operator" of "support" on the same installation model
    When a caller sends a message to "helper" and assigns a task to "operator"
    Then the caller of "helper" is told the model provider is unavailable
    And the task of "operator" has a failed iteration naming the capability
    And "support" is ready

  Scenario: an agent on a model with the capabilities it needs is answered
    Given an installation whose model server serves the model "qwen3:0.6b", which has the capability "tool calling"
    And a deployed service "support" with an agent "helper" on the installation model "qwen3:0.6b"
    And a tool "lookup" of "helper"
    When a caller sends a message to "helper" that makes the model call "lookup"
    Then the tool "lookup" runs
    And the caller is answered
