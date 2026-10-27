Feature: The documentation of MCP endpoints
  A developer learns from the documentation how to declare an MCP endpoint and its tools in each
  language, how to state who may call it, how to issue and revoke client keys, what a client that
  signs in is told, and what the platform does not do. A coding agent building a service on the
  developer's machine is told the service's tools beside its routes.

  Scenario: a coding agent is told a local service's tools
    Given a coding agent holding the platform's skills
    And a service "cart" running on a developer's machine with an MCP endpoint at "/mcp" whose tools are "get_cart" and "add_item"
    When the coding agent asks what "cart" serves
    Then the coding agent is told the MCP endpoint "/mcp" with the tools "get_cart" and "add_item" beside the routes of "cart"

  Scenario: the documentation describes declaring an MCP endpoint, its ACL and its client keys in every language
    Given the published documentation
    When a reader reads about MCP endpoints
    Then the documentation describes declaring an MCP endpoint and its tools in each language
    And the documentation describes stating who may call it, in each of the three kinds
    And the documentation describes issuing, listing and revoking client keys
    And the documentation describes what a client that signs in is told, and when to opt into brokered sign-in
    And every sample shown there is taken from code a test runs

  Scenario: the documentation says what an MCP endpoint does not do
    Given the published documentation
    When a reader reads what ankka does not do about MCP endpoints
    Then the documentation says an MCP endpoint offers tools and no resources or prompts
    And the documentation says an MCP endpoint keeps nothing between requests
    And the documentation says a change to a service's tools is seen by an MCP client at its next listing
    And the documentation says there is no one MCP endpoint for every service of a project
    And the documentation says the platform holds no account and no password for the people a service serves, and mints a token for them only through a sign-in broker the service opted into
