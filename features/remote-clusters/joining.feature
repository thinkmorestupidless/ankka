Feature: A remote cluster joins the installation
  An installation has a home cluster, where the control plane runs, and may have remote clusters.
  A platform administrator creates a remote cluster and is shown its cluster token once. The
  operator on that cluster tethers to the control plane with the cluster token, declaring its version
  and its domain, and the cluster is listed as tethered. The control plane holds no address and no
  credential for any remote cluster; the cluster reaches the control plane, never the reverse.

  Background:
    Given a platform administrator "root"
    And an installation whose base domain is "example.com"

  Scenario: a platform administrator creates a remote cluster and is shown its cluster token once
    When "root" creates the remote cluster "pi" with the domain "pi.example.com"
    Then "root" is shown the cluster token of "pi" once
    And the cluster token of "pi" is never shown again
    And the platform keeps a digest of the cluster token and not the cluster token
    And "pi" is listed as untethered

  Scenario: an operator given the cluster token and the address of the control plane tethers and the cluster is listed as tethered
    Given a remote cluster "pi" with the domain "pi.example.com" whose cluster token was shown to "root"
    And an operator on "pi" given the cluster token of "pi" and the address of the control plane, whose base domain is "pi.example.com"
    When the operator starts
    Then the operator tethers to the control plane
    And "pi" is listed as tethered, with when it was last seen and the version of its operator
    And the control plane holds no address and no credential for "pi"

  Scenario: a cluster token is good for one cluster and for nothing else
    Given a remote cluster "pi" whose cluster token was shown to "root"
    When a person calls the control plane as a member with the cluster token of "pi"
    Then the call is refused with 401
    When an operator on another cluster tethers with the cluster token of "pi", declaring the domain "other.example.com"
    Then the tether is refused, saying that the domain is not the cluster's

  Scenario: renewing the cluster token stops the old one admitting the cluster at once
    Given a remote cluster "pi" that is tethered with its cluster token
    When "root" renews the cluster token of "pi"
    Then "root" is shown the new cluster token of "pi" once
    And the tether of "pi" is closed, saying that the cluster token was renewed
    And an operator that tethers with the old cluster token of "pi" is refused
    And an operator that tethers with the new cluster token of "pi" tethers

  Scenario: an operator at a version the platform does not accept is refused the tether with the reason
    Given a remote cluster "pi" whose cluster token was shown to "root"
    And an operator on "pi" at a version the platform does not accept
    When the operator tethers
    Then the tether is refused, naming the version of the operator and the versions the platform accepts
    And the operator logs the reason and tries again later

  Scenario: an operator declaring a domain that is not the cluster's is refused the tether with the reason
    Given a remote cluster "pi" with the domain "pi.example.com" whose cluster token was shown to "root"
    And an operator on "pi" whose base domain is "home.example.com"
    When the operator tethers
    Then the tether is refused, naming "home.example.com" and "pi.example.com"
    And the operator logs the reason and tries again later

  Scenario: a member who is not a platform administrator cannot create, renew or delete a cluster
    Given an organization "acme" whose owner is "ada", who is not a platform administrator
    And a remote cluster "pi"
    When "ada" creates the remote cluster "garage" with the domain "garage.example.com"
    Then the creation is refused with 403
    When "ada" renews the cluster token of "pi"
    Then the request is refused with 403
    When "ada" deletes the remote cluster "pi"
    Then the deletion is refused with 403

  Scenario: a cluster with projects placed on it cannot be deleted
    Given a remote cluster "pi"
    And a project "shop" placed on "pi"
    When "root" deletes the remote cluster "pi"
    Then the deletion is refused, naming "shop"
    And "pi" is still listed

  Scenario: deleting a cluster with no projects stops its cluster token admitting and unlists it
    Given a remote cluster "pi" that is tethered, with no project placed on it
    When "root" deletes the remote cluster "pi"
    Then the tether of "pi" is closed, saying that the cluster was deleted
    And an operator that tethers with the cluster token of "pi" is refused
    And "pi" is listed no more
    And no remote cluster "pi" can be created again

  Scenario: the home cluster is listed as home and cannot be deleted
    When "root" lists the clusters
    Then "root" is shown the home cluster, with the domain "example.com", with no cluster token and no tether
    When "root" deletes the home cluster
    Then the deletion is refused, saying that the home cluster cannot be deleted
