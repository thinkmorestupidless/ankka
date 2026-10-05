Feature: Reaching the control plane from outside the cluster
  The control plane answers at a hostname of its own under the installation's base domain, through
  the gateway, as an exposed service does. A member operates the platform from their own machine
  with the command line alone, holding no credential for the cluster.

  Scenario: installing the platform says where the control plane answers and how to trust it
    When a person installs the platform on a cluster on their own machine
    Then the person is shown the address of the control plane
    And the person is shown where the certificate of the installation's authority was written
    And the person is shown how to have the command line use both

  Scenario: a member operates the platform with no credential for the cluster
    Given a member whose command line uses the address of the control plane
    And the member's machine holds no credential for the cluster
    When the member lists the services of the project "shop"
    Then the member is shown the services of the project "shop"
