Feature: Client keys for a service's MCP endpoint
  A service issues a client key for one caller of its MCP endpoint: a subject its handlers read as
  the principal, a scope of tools, something it is labelled and when it expires. The service's own
  code issues it, since only the service knows who its callers are, and the platform keeps it so
  that no service writes its own. The client key is shown once; the service lists and revokes its
  client keys; a revoked one is refused on its next request on every instance.

  Background:
    Given a service "plumbers" with an MCP endpoint whose tools are "list_jobs", "book_job", "reschedule_job" and "close_books"
    And the ACL of the MCP endpoint admits client keys

  Scenario: a service issues a client key for a subject and is shown it once
    When a handler of "plumbers" issues a client key for the subject "acme", labelled "Alice's assistant", expiring in one year
    Then the handler is told the client key's id and the client key
    And the client key is shown nowhere again

  Scenario: a request carrying a client key runs the tool as the client key's subject
    Given a client key of "plumbers" for the subject "acme"
    When an MCP client calls "book_job" carrying the client key
    Then the handler of "book_job" is told a principal whose subject is "acme"

  Scenario: a handler reads a client key's attributes as the principal's claims
    Given a client key of "plumbers" for the subject "acme" with the attributes "person alice" and "role owner"
    When an MCP client calls "book_job" carrying the client key
    Then the handler of "book_job" is told a principal whose subject is "acme", whose claim "person" is "alice" and whose claim "role" is "owner"

  Scenario Outline: a client key's attributes beyond what the platform keeps are refused at issue
    When a handler of "plumbers" issues a client key for the subject "acme" with <attributes>
    Then the handler is refused
    And the refusal names the attributes

    Examples:
      | attributes                                  |
      | seventeen attributes                        |
      | an attribute whose name is 257 characters   |
      | an attribute whose value is 257 characters  |

  Scenario: a client key scoped to tools is shown those tools and no other
    Given a client key of "plumbers" for the subject "acme" scoped to the tools "list_jobs", "book_job" and "reschedule_job"
    When an MCP client asks for the tools of "plumbers" carrying the client key
    Then the MCP client is told the tools "list_jobs", "book_job" and "reschedule_job"
    And the MCP client is not told the tool "close_books"

  Scenario: a tool call outside a client key's scope is refused, naming the tool
    Given a client key of "plumbers" for the subject "acme" scoped to the tools "list_jobs"
    When an MCP client calls "close_books" carrying the client key
    Then the MCP client is told a protocol error naming "close_books"
    And the handler of "close_books" is not run

  Scenario: a client key scoped to no tool may call every tool of the MCP endpoint
    Given a client key of "plumbers" for the subject "acme" scoped to no tool
    When an MCP client asks for the tools of "plumbers" carrying the client key
    Then the MCP client is told the tools "list_jobs", "book_job", "reschedule_job" and "close_books"

  Scenario Outline: a client key that cannot be issued is refused, naming what is wrong
    When a handler of "plumbers" issues a client key <issued>
    Then the handler is refused
    And the refusal names <wrong>

    Examples:
      | issued                                                        | wrong                   |
      | for the subject "acme" scoped to the tool "empty_cart"        | the tool "empty_cart"   |
      | for a blank subject                                           | the subject             |
      | for the subject "acme" that expired yesterday                 | the expiry              |
      | for the subject "acme" with no expiry given                   | the expiry              |

  Scenario: a service lists its client keys with each one's label, expiry and last use, and never the client key itself
    Given a client key of "plumbers" for the subject "acme", labelled "Alice's assistant", that an MCP client has used
    When a handler of "plumbers" lists the client keys of "plumbers"
    Then the client key is listed with its id, its subject "acme", its attributes, "Alice's assistant", its scope, when it was issued, when it expires and when it was last used
    And nothing listed reads as the client key

  Scenario: a revoked client key is refused on its next request on every instance
    Given "plumbers" is deployed with 3 instances
    And a client key of "plumbers" for the subject "acme"
    When a handler of "plumbers" revokes the client key
    Then within one second a request carrying the client key is refused at every instance
    And no handler of a tool is run for it

  Scenario: a client key issued as never expiring is admitted after any time
    Given a client key of "plumbers" for the subject "acme" issued as never expiring
    When an MCP client calls "list_jobs" carrying the client key two years later
    Then the handler of "list_jobs" is told a principal whose subject is "acme"

  Scenario: an expired client key is refused, saying it expired
    Given a client key of "plumbers" for the subject "acme" that has since expired
    When an MCP client calls "list_jobs" carrying the client key
    Then the request is refused
    And the refusal says the client key expired

  Scenario: a client key survives the service restarting
    Given a client key of "plumbers" for the subject "acme"
    When "plumbers" restarts
    Then a request carrying the client key runs "list_jobs" as the subject "acme"

  Scenario: a client key of one service opens nothing on another
    Given a service "office" with an MCP endpoint whose ACL admits client keys
    And a client key of "plumbers" for the subject "acme"
    When an MCP client calls a tool of "office" carrying the client key
    Then the request is refused
    And no handler of "office" is run

  Scenario: a client key appears in no log, trace or listing
    Given a client key of "plumbers" for the subject "acme" that an MCP client has used to call "book_job"
    When a developer reads the trace of that request, the logs of "plumbers" and a listing of its client keys
    Then the trace shows the tool call to "book_job" as the subject "acme" with the client key's id
    And nothing in the trace, the logs or the listing reads as the client key
