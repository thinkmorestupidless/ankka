Feature: Naming an installation model
  An installation model is chosen by its name, so the names must be readable and a wrong one said
  plainly. A developer reads the served models in the status of the installation
  (features/installation-models/serving.feature); a service naming a model
  the cluster does not serve, or naming one on a cluster with no model server, fails to start naming
  what is served; a service naming a declared model not yet served waits for it.

  Scenario: a service naming a model the installation does not serve fails to start naming the served models
    Given an installation whose model server serves the model "qwen3:0.6b"
    And a descriptor for a service "support" that names a model provider with the preset "installation" and the model "mistral:7b"
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" says that the installation model "mistral:7b" is not served, and names "qwen3:0.6b"

  Scenario: a service naming an installation model on a cluster without a model server fails to start saying so
    Given an installation whose home cluster has a model server and whose remote cluster "pi" has none
    And a project placed on "pi"
    And a descriptor for a service "support" in that project that names a model provider with the preset "installation" and the model "qwen3:0.6b"
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" says that the cluster "pi" has no model server

  Scenario: a service naming a declared model not yet fetched waits for it and starts when it is served
    Given an installation whose model server declares the model "llama3.2:1b" and is still fetching it
    And a descriptor for a service "support" that names a model provider with the preset "installation" and the model "llama3.2:1b"
    When a member applies the descriptor
    Then the lifecycle of "support" is "UpdateInProgress", because "llama3.2:1b" is still being fetched
    And when "llama3.2:1b" becomes a served model, "support" is ready

  Scenario Outline: a descriptor that gives an address or a key beside the preset installation is refused at the service's start
    Given a descriptor for a service "support" that names a model provider with the preset "installation", the model "qwen3:0.6b" and <also>
    When a member applies the descriptor
    Then the lifecycle of "support" is "Failed"
    And the detail of "support" says that an installation model takes no <what>

    Examples:
      | also      | what    |
      | an address | address |
      | a key      | key     |

  Scenario: a service run outside an installation that names the preset installation is told there is no model server
    Given a local service "support" that names a model provider with the preset "installation" and the model "qwen3:0.6b"
    When the developer runs "support"
    Then "support" does not start
    And the developer is told that there is no installation model server here, and where the documentation describes a model server of their own
