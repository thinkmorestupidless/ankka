Feature: Pulling from a private registry
  A member registers, once for a project, the credential the cluster pulls that project's images
  with, and every service in the project can pull from that registry. The password is given to the
  cluster and never shown back.

  Background:
    Given a project "shop"
    And a member of the organization "shop" is in

  Scenario: a service whose image is in a private registry pulls it with its project's registry credential
    Given a registry credential for the registry "ghcr.io" registered for "shop"
    When the member applies a descriptor for a service "orders" in "shop" whose image is in "ghcr.io" and private
    Then "orders" is ready

  Scenario: a service with a public image needs no registry credential
    Given no registry credential is registered for "shop"
    When the member applies a descriptor for a service "orders" in "shop" whose image is public
    Then "orders" is ready

  Scenario: a service that cannot pull its image says so
    Given no registry credential is registered for "shop"
    When the member applies a descriptor for a service "orders" in "shop" whose image is in a private registry
    Then "orders" does not become ready
    And what the platform shows of "orders" says that its image could not be pulled

  Scenario: a registry credential's password is never shown back
    Given a registry credential for the registry "ghcr.io" with the username "octocat" and the password "p4ss" registered for "shop"
    When the member reads the project "shop"
    Then it names the registry "ghcr.io" and the username "octocat"
    And it shows "p4ss" nowhere
