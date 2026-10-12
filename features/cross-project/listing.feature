Feature: Listing a project's grants
  A project's members read who it has granted what, who granted each and when, and whether each
  grant is in effect or why not. A project's members also read what its services hold from other
  projects, and an organization's members what its registered machines hold. Every grant and every
  revocation is in the project's history with the owner who made it, and a member of neither
  project sees nothing of a grant between them.

  Background:
    Given the projects "spinvibe" and "payments" of the organization "eitheror"
    And "ada" is an owner of "eitheror"
    And a deployed service "wallet" in "spinvibe" with an HTTP endpoint whose ACL admits granted callers, with the route "POST /v1/wallets/{player}/{currency}/deposits"
    And a deployed service "merchant" in "payments"

  Scenario: a project lists who it has granted what, and who granted it
    Given "ada" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    When a member of "eitheror" reads the grants of "spinvibe"
    Then the member is shown the grant to the service "merchant" of "payments" on the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet", granted by "ada", with when it was granted
    And the grant is shown as in effect

  Scenario Outline: a project lists what its services and machines were granted by other projects
    Given "ada" has registered "psp-batch" as a machine of "eitheror"
    And "ada" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    And "ada" has granted the registered machine "psp-batch" of "eitheror" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    When a member of "eitheror" reads the grants held by <holder>
    Then the member is shown the grant to <grantee> on the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet" of "spinvibe", granted by "ada"

    Examples:
      | holder                      | grantee                                          |
      | the project "payments"      | the service "merchant" of "payments"             |
      | the organization "eitheror" | the registered machine "psp-batch" of "eitheror" |

  Scenario Outline: a grant that opens nothing yet says why
    Given <situation>
    And "ada" has granted <grantee> <target>
    When a member of "eitheror" reads the grants of "spinvibe"
    Then the grant is shown as not in effect, "<why>"

    Examples:
      | situation                                                                                            | grantee                                    | target                                                                      | why                 |
      | "wallet" has no route "GET /v1/wallets/{player}/history" yet                                         | the service "merchant" of "payments"       | the route "GET /v1/wallets/{player}/history" of "wallet"                    | route not seen      |
      | "wallet" has the route "GET /v1/wallets/{player}" whose ACL admits only the service "lobby"          | the service "merchant" of "payments"       | the route "GET /v1/wallets/{player}" of "wallet"                            | route not grantable |
      | "spinvibe" has a web-hosted service "web"                                                            | the service "merchant" of "payments"       | the route "GET /" of "web"                                                  | route not grantable |
      | "wallet" was deployed by a platform too old to hand grants to a running instance                     | the service "merchant" of "payments"       | the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"       | rollout needed      |
      | the installation does not expose its broker, and the topic "affiliates.attribution" is declared on "spinvibe" | the registered machine "psp-batch" of "eitheror" | to consume the topic "affiliates.attribution" of "spinvibe"      | broker not exposed  |
      | the organization "affiliates" has the project "network" with a deployed service "ingest"             | the service "ingest" of "network"          | the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"       | pending             |

  # A topic's retention is declared once a project can say it; until then a grant tells its
  # grantee the partitions and compaction it can read, and this waits.
  @ignore
  Scenario: a topic grant tells its grantee the topic's retention
    Given the topic "affiliates.attribution" is declared on "spinvibe", and the broker retains its messages for "7" days
    And "ada" has granted the service "merchant" of "payments" to consume the topic "affiliates.attribution" of "spinvibe"
    When a member of "eitheror" reads the grants held by the project "payments"
    Then the grant is shown with how long the broker retains the messages of the topic, "7" days, and whether the topic is compacted

  Scenario: a member of neither project sees no grant between them
    Given "ada" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    And a person who is not a member of the organization "eitheror" is in
    When that person reads the grants of "spinvibe"
    Then that person is told that there is no project "spinvibe"

  Scenario: granting and revoking are recorded with the owner who did it
    Given "ada" has granted the service "merchant" of "payments" the route "POST /v1/wallets/{player}/{currency}/deposits" of "wallet"
    And "ada" has since revoked the grant
    When a member of "eitheror" reads the history of "spinvibe"
    Then the history shows that "ada" granted it and that "ada" revoked it, each with when it was done
    And nothing in the history holds a credential or a machine token

  Scenario: a grant that allows decryption and an erasure grant are held and listed, and nothing enforces them yet
    Given the topic "casino.players" is declared on "spinvibe"
    And "ada" has granted the service "merchant" of "payments" to consume the topic "casino.players" of "spinvibe", allowing decryption
    And "ada" has granted the service "merchant" of "payments" the right to ask for the erasure of the data subjects of "spinvibe"
    When a member of "eitheror" reads the grants of "spinvibe"
    Then the member is shown the grant to consume "casino.players" as allowing decryption, and the erasure grant, each granted by "ada"
    And the grants held by the project "payments" show both
    And neither grant opens a route of "spinvibe", and nothing of "spinvibe" decrypts or erases anything for "merchant"
