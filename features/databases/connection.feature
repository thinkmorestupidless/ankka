Feature: How a service connects to its database
  A service connects to its provisioned database over a mutually authenticated connection. The service checks the
  database's certificate against the authority the platform told it to trust, and the database
  knows the service by the certificate the platform issued it. There is no password to write down,
  keep or leak, and a service that declares a database of its own decides how it is checked.

  Scenario: a service's connection to its provisioned database is mutually authenticated
    Given a deployed service "cart" with a provisioned database
    When "cart" connects to its database
    Then the database reports the connection as mutually authenticated with the certificate of "cart"
    And "cart" checked the certificate of the database before showing its own

  Scenario: a provisioned database is reached with no password
    Given a deployed service "cart" whose descriptor says nothing of a database
    When an instance of "cart" starts
    Then the instance is given the certificate the platform issued "cart" and no password
    And the database of "cart" refuses any connection made with a password

  Scenario: a service does not connect to a database whose certificate it was not told to trust
    Given a deployed service "cart" whose database shows a certificate the authority "cart" trusts did not issue
    When "cart" starts
    Then "cart" does not connect to the database
    And "cart" reports that the certificate of the database did not verify

  Scenario: a service whose database was provisioned with a password is moved to its certificate when it is next deployed
    Given a deployed service "cart" whose database was provisioned with a password
    And "cart" has recorded an event
    When a member deploys "cart" again
    Then "cart" connects to its database with its certificate
    And the database of "cart" refuses a connection made with the password
    And "cart" still holds the event

  Scenario: a service that declares a database of its own checks it as its descriptor says
    Given a descriptor for the service "cart" that declares a database of its own, how to check it, and the authority to trust
    When a member applies the descriptor
    Then "cart" connects to its database only once the certificate of the database verifies against that authority

  Scenario: a service that declares a database of its own and nothing to check it with connects unchecked
    Given a descriptor for the service "cart" that declares a database of its own and nothing to check it with
    When a member applies the descriptor
    Then "cart" connects to its database without checking its certificate
    And the documentation says what connecting unchecked risks
