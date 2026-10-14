Feature: Placing a project on a cluster
  A project is placed on one cluster when it is created, and stays there: its databases, its
  topics, its buckets and its services are on that cluster. A project created with no cluster named
  is on the home cluster. A remote cluster admits every organization, or names the organizations it
  admits.

  Background:
    Given a platform administrator "root"
    And an organization "acme" whose owner is "ada", who is not a platform administrator

  Scenario: a project is placed on a remote cluster when it is created
    Given a remote cluster "pi" that admits every organization
    When "ada" creates the project "shop" in "acme" placed on "pi"
    Then "ada" is shown the project "shop" placed on "pi"
    And the project listing of "acme" names "pi" for "shop"

  Scenario: a project created with no cluster named is on the home cluster
    When "ada" creates the project "shop" in "acme"
    Then "ada" is shown the project "shop" placed on the home cluster

  Scenario: a member is shown the clusters their organization is admitted to
    Given a remote cluster "pi" that admits every organization
    And a remote cluster "annex" that admits the organization "globex" alone
    When "ada" lists the clusters
    Then "ada" is shown the home cluster and "pi", each with its domain
    And "ada" is not shown "annex"

  Scenario: a project cannot be placed on a cluster that does not admit its organization
    Given a remote cluster "annex" that admits the organization "globex" alone
    When "ada" creates the project "shop" in "acme" placed on "annex"
    Then the creation is refused with 404
    And "acme" has no project "shop"

  Scenario: a project cannot be placed on a cluster that does not exist
    When "ada" creates the project "shop" in "acme" placed on "nowhere"
    Then the creation is refused with 404
    And "acme" has no project "shop"

  Scenario: a platform administrator changes which organizations a cluster admits
    Given a remote cluster "annex" that admits the organization "globex" alone
    When "root" admits the organization "acme" to "annex"
    Then "ada" is shown "annex" among the clusters
    And "ada" creates the project "shop" in "acme" placed on "annex"

  Scenario: a project cannot be moved to another cluster
    Given a project "shop" in "acme" placed on "pi"
    When "ada" asks for "shop" to be placed on the home cluster instead
    Then the request is refused, saying that a project stays on the cluster it was placed on

  Scenario: every project that existed before clusters is on the home cluster
    Given a project "legacy" in "acme" created before the installation had clusters
    When "ada" is shown the project "legacy"
    Then "legacy" is placed on the home cluster
