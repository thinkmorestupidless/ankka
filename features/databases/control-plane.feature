Feature: The control plane's own database
  The control plane keeps the only record of what every service should be. Its database is
  provisioned with the installation, the way a service's is, and what it records survives a restart
  of that database.

  Scenario: the control plane's database is provisioned with the installation
    When the platform is installed in a cluster
    Then a database is provisioned for the control plane
    And every instance of the control plane is ready, connected to its database

  Scenario: what the control plane recorded survives a restart of its database
    Given a member has applied descriptors for the services "cart" and "payments" in the project "shop"
    When the database of the control plane is restarted
    Then a member who lists the services of the project "shop" is shown "cart" and "payments"

  Scenario: a member is told that the control plane cannot reach its database, and is not shown nothing
    Given the control plane cannot reach its database
    When a member lists the services of the project "shop"
    Then the member is shown that the control plane cannot reach its database
    And the member is not shown an empty list of services
