Feature: Every SDK calls another service the same way
  The conformance suite holds every SDK to the same call to another service, so an SDK that gets
  its shape wrong, and every SDK written later, is found out before a developer uses it.

  Scenario Outline: the conformance suite's call to another service passes for every SDK
    Given the conformance suite run against the "<sdk>" SDK
    When a handler calls a scripted service through the platform's own program
    Then the scripted service is given the call exactly as the handler made it
    And the handler is given the answer exactly as the scripted service made it

    Examples:
      | sdk        |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: an SDK's type checks accept a call to another service
    Given the "<sdk>" SDK built from the protocol that has calls to other services
    When the type checks of the SDK run
    Then they pass

    Examples:
      | sdk        |
      | Python     |
      | TypeScript |
