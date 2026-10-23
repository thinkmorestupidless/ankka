Feature: An MCP endpoint in every language
  A Python, TypeScript, Rust or Go service declares an MCP endpoint in its own idiom, and the platform's
  own program answers the protocol for it: it verifies the credential, checks the arguments and asks
  the service's own code to run the tool. The service's own code reads the principal and never the
  credential, and issues, lists and revokes client keys through its client.

  Scenario Outline: a Python, TypeScript or Rust service declares an MCP endpoint and the platform answers the protocol for it
    Given a service "cart" written in "<language>" with an MCP endpoint whose tool is "get_cart"
    When an MCP client asks the MCP endpoint for its tools
    Then the MCP client is told the tool "get_cart" with the description and the arguments it declares
    And nothing in the developer's code spoke to the MCP client

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario Outline: a service's own code is asked to run a tool with typed arguments and the principal, and never sees the credential
    Given a service "cart" written in "<language>" with an MCP endpoint whose tool is "get_cart" and whose ACL admits client keys
    And a client key of "cart" for the subject "acme"
    When an MCP client calls "get_cart" with the arguments "cartId c1" carrying the client key
    Then the developer's code is asked to run "get_cart" with the cart "c1" and a principal whose subject is "acme"
    And the developer's code is given nothing that reads as the client key

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario Outline: a service's own code issues, lists and revokes client keys through its client
    Given a service "cart" written in "<language>" with an MCP endpoint whose tool is "get_cart" and whose ACL admits client keys
    When the developer's code <does> through its client
    Then <outcome>

    Examples:
      | language   | does                                              | outcome                                                                 |
      | Python     | issues a client key for the subject "acme"       | a request carrying the client key runs "get_cart" as the subject "acme" |
      | Python     | lists the client keys of "cart"                   | each client key is listed with its id and subject and never itself      |
      | Python     | revokes a client key                             | a request carrying the client key is refused                            |
      | TypeScript | issues a client key for the subject "acme"       | a request carrying the client key runs "get_cart" as the subject "acme" |
      | TypeScript | lists the client keys of "cart"                   | each client key is listed with its id and subject and never itself      |
      | TypeScript | revokes a client key                             | a request carrying the client key is refused                            |
      | Rust       | issues a client key for the subject "acme"       | a request carrying the client key runs "get_cart" as the subject "acme" |
      | Rust       | lists the client keys of "cart"                   | each client key is listed with its id and subject and never itself      |
      | Rust       | revokes a client key                             | a request carrying the client key is refused                            |
      | Go         | issues a client key for the subject "acme"       | a request carrying the client key runs "get_cart" as the subject "acme" |
      | Go         | lists the client keys of "cart"                   | each client key is listed with its id and subject and never itself      |
      | Go         | revokes a client key                             | a request carrying the client key is refused                            |
