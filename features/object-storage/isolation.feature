Feature: A storage credential reaches one bucket
  Each service that asks for a bucket has a bucket of its own and a storage credential that
  reaches that bucket and no other, in its own project or in another. The platform makes the
  storage credential once, keeps it where a starting instance is given it, and can never read it
  back. It is replaced only when a member asks for it to be issued again; the old one is refused
  from then on.

  Scenario Outline: a storage credential is refused by another service's bucket
    Given a deployed service "reports" with a bucket in the project "shop"
    And a deployed service "exports" with a bucket in the project "<project>"
    When "reports" reads the bucket of "exports" with its own storage credential
    Then the object store refuses "reports"

    Examples:
      | project |
      | shop    |
      | bank    |

  Scenario: the platform cannot read a storage credential back
    Given a deployed service "reports" with a bucket
    When the platform tries to read the storage credential of "reports"
    Then the platform is refused

  Scenario: a storage credential is made once and replaced only when a member asks
    Given a deployed service "reports" with a bucket
    When a member applies the descriptor of "reports" again
    Then the storage credential of "reports" is the one it had before

  Scenario: a storage credential issued again at a member's asking replaces the one the service had
    Given a deployed service "reports" with a bucket
    When a member asks for the storage credential of "reports" to be issued again
    Then "reports" reads its bucket with a storage credential that is not the one it had before, once it is restarted
    And the object store refuses the storage credential "reports" had before
    And the history of "reports" says that its storage credential was issued again

  Scenario Outline: a descriptor cannot take a variable from the secret that holds a storage credential
    Given a deployed service "exports" with a bucket in the project "shop"
    And a descriptor for a service "<service>" in the project "shop" with a variable taken from the secret that holds the storage credential of "exports"
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the variable and the secret

    Examples:
      | service |
      | reports |
      | exports |
