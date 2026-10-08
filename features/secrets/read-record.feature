Feature: Every read of a secret leaves a record
  On either secret backend the platform keeps a read record of every read of a service secret,
  and of every keep and removal: the secret's name, the project, the service, its hosting, the
  outcome, the time, the trace id and the request where known, and the component and its kind
  where the caller can be known; never the value. The control plane keeps it, never the service's
  database, so a service cannot remove the record of its own reads and a restore of its database
  does not rewind it. It is kept for the installation's retention and an owner may list it. On
  the Secret Manager backend the access log records each access beside it.

  Scenario: the access log records a read of a service secret
    Given an installation on the Secret Manager backend with the access log on
    And a deployed service "payments" that has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" reads the service secret "acme"
    Then the access log holds the access, naming the identity of "payments" and the service secret "acme"

  Scenario Outline: a read of a service secret is recorded with what is known of it and never the value
    Given an installation on the <backend>
    And a deployed service "payments" in the project "spinvibe" that has kept "sk-acme-1" as the service secret "acme"
    When a handler of a component of "payments" reads the service secret "acme" while it serves a request
    Then the read record names the service secret "acme", the project "spinvibe", the service "payments", its hosting, the time, the request and the trace id
    And the read record names the component and its kind
    And the read record holds "sk-acme-1" nowhere

    Examples:
      | backend                |
      | Postgres backend       |
      | Secret Manager backend |

  Scenario Outline: a read that finds none or is refused is recorded with its outcome
    Given an installation on the <backend>
    And a deployed service "payments"
    When a handler of "payments" reads <what>
    Then the read record names the service secret "acme" and the outcome <outcome>

    Examples:
      | backend                | what                                                           | outcome   |
      | Postgres backend       | the service secret "acme", which was never kept                | "none"    |
      | Secret Manager backend | the service secret "acme", which was never kept                | "none"    |
      | Secret Manager backend | the service secret "acme" before its secret access was written | "refused" |

  Scenario: an installation whose access log is off is told what to turn on
    Given an installation on the Secret Manager backend with the access log off
    When the installation starts
    Then the installation's status says that the access log is off, and names what to turn on

  Scenario: an owner is answered from the read record which services read a secret on the Postgres backend
    Given an installation on the Postgres backend
    And a deployed service "payments" that has read the service secret "acme" 3 times
    When an owner asks which services read the service secret "acme" between two times
    Then the owner is told "payments" and each time it read
    And no service is refused for running on the Postgres backend

  Scenario: the read record outlives a restore of the service's database
    Given a deployed service "payments" that read the service secret "acme" after a point in time
    When the database of "payments" is restored to that point
    Then the read record still holds every read made after it

  Scenario Outline: a read through a process or a module is recorded without a component
    Given a deployed service "payments" written in "<language>" that has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" reads the service secret "acme"
    Then the read record names the service "payments" and its hosting
    And the read record names no component

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario: a read record whose retention has passed is removed
    Given an installation whose retention is "1 year"
    And a read record of "payments" older than "1 year"
    When an owner lists the read record of "payments"
    Then that read record is not listed
    And the installation's status shows the retention "1 year"
