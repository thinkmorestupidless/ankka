Feature: Service secrets in every language
  A service keeps and reads service secrets the same way whether it is written in Scala, Python,
  TypeScript or Rust, because the platform's own program holds the secret key and the secret
  store, and the developer's program asks it.

  Scenario Outline: a service secret is kept and read in every language
    Given a service "payments" written in "<language>" with a secret key
    And a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" reads the service secret "acme"
    Then the handler is told "sk-acme-1"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the database holds a service secret only encrypted in every language
    Given a service "payments" written in "<language>" with a secret key
    When a handler of "payments" keeps "sk-acme-1" as the service secret "acme"
    Then the secret store of "payments" holds the service secret "acme" encrypted
    And nothing in the database of "payments" reads as "sk-acme-1"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service secret that was never kept is read as none in every language
    Given a service "payments" written in "<language>" with a secret key
    When a handler of "payments" reads the service secret "acme"
    Then the handler is told that there is no service secret "acme"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: keeping a service secret again replaces its value in every language
    Given a service "payments" written in "<language>" with a secret key
    And a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When a handler of "payments" keeps "sk-acme-2" as the service secret "acme"
    Then a handler that reads the service secret "acme" is told "sk-acme-2"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
