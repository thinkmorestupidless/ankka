Feature: A member works with erasure requests wherever the platform is used
  A member asks for, reads, withdraws and overrides erasure requests, and fetches erasure
  certificates, through the command line, the console and the platform's MCP server alike. The
  platform's MCP server acts as the member who is logged in, so a model that uses its tools can do
  no more than that member can.

  Background:
    Given a project "brand" with the service "players"

  Scenario Outline: a member asks for an erasure request through <place>
    When a member of "brand" asks for an erasure request for "player/8c1f" in "brand" through <place>
    Then the erasure request is accepted
    And it names the member as who asked for it

    Examples:
      | place                        |
      | the command line             |
      | the console                  |
      | the platform's MCP server    |

  Scenario: the console shows the erasure requests of a project with where each one stands and each service's completion
    Given an applied erasure request for "player/8c1f" in "brand"
    And a held erasure request for "player/41d2" in "brand"
    When a member of "brand" reads the erasure requests of "brand" in the console
    Then the console shows the erasure request for "player/8c1f" as applied, with the completion of "players"
    And the console shows the erasure request for "player/41d2" as held, with its not-before date

  Scenario Outline: a member fetches the erasure certificate of an applied erasure request through <place>
    Given an applied erasure request for "player/8c1f" in "brand"
    When a member of "brand" fetches its erasure certificate through <place>
    Then the member is given the erasure certificate of the erasure request

    Examples:
      | place                        |
      | the command line             |
      | the console                  |
      | the platform's MCP server    |

  Scenario Outline: only an owner overrides a hold, <who> through <place>
    Given a held erasure request for "player/8c1f" in "brand"
    When <who> of "brand" overrides its hold with the reason "regulator order 2026-114" through <place>
    Then <outcome>

    Examples:
      | who                          | place                     | outcome                                |
      | an owner                     | the console               | the erasure request is applied at once |
      | an owner                     | the platform's MCP server | the erasure request is applied at once |
      | a member who is not an owner | the console               | the override is refused                |
      | a member who is not an owner | the platform's MCP server | the override is refused                |
