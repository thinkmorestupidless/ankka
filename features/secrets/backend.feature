Feature: The installation's secret backend
  Where a service's secret store keeps its service secrets, and where a project secret's entries
  are kept, is the installation's to say, once: its databases and the cluster's secrets, as
  before, or Secret Manager. A service's code, its descriptor and what its components ask of the
  secret store are the same on either. A developer's machine and a test need no Google Cloud: a
  local platform is on the Postgres backend unless told otherwise, and the test kit gives a test
  that asks for the Secret Manager backend a Secret Manager fake.

  Scenario: a local platform whose secret backend is not set is on the Postgres backend
    Given a local platform whose secret backend is not set
    When a service "payments" with a secret key starts
    Then "payments" keeps its service secrets in its database, encrypted with its secret key
    And "payments" reaches no Google Cloud

  Scenario: a test that asks for the Secret Manager backend is given the Secret Manager fake
    Given a test that starts the service "payments" with the test kit
    When the test asks for the Secret Manager backend
    Then "payments" keeps its service secrets in the Secret Manager fake
    And "payments" reaches no network and holds no credential for Google Cloud

  Scenario: the Secret Manager fake refuses what Google Cloud would refuse
    Given a test that starts the services "payments" and "wallet" with the test kit on the Secret Manager backend
    And a handler of "payments" has kept "sk-acme-1" as the service secret "acme"
    When "wallet" reads the service secret "acme" of "payments" from the Secret Manager fake as its own identity
    Then the Secret Manager fake refuses "wallet", as Google Cloud would
