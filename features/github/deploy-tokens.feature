Feature: Deploy tokens
  An owner of an organization gives a machine a credential of its own, a deploy token, with nobody
  else involved. A deploy token acts as an ordinary member of its organization, and never as an
  owner. It is shown once, when it is created, and expires unless its owner says otherwise.

  Background:
    Given an organization "acme"
    And an owner of "acme"

  Scenario: a deploy token is shown once, when it is created
    When the owner creates a deploy token labelled "github-deploy"
    Then the owner is shown the deploy token
    And the deploy token is never shown again

  Scenario: a list of deploy tokens shows who created each and when it was last used, and never a deploy token itself
    Given the owner created a deploy token labelled "github-deploy" that has been used today
    When the owner lists the deploy tokens of "acme"
    Then the list shows "github-deploy" with who created it, when, when it expires and today's date as its last use
    And the list shows no deploy token itself

  Scenario: a change made with a deploy token is attributed to the deploy token
    Given the owner created a deploy token labelled "github-deploy"
    When a machine holding the deploy token applies a descriptor for a service "orders" in a project of "acme"
    Then the history of "orders" attributes the change to the deploy token "github-deploy"
    And not to the owner who created it

  Scenario Outline: a deploy token may not do what only an owner may
    Given a deploy token of "acme"
    When a machine holding the deploy token <asks>
    Then the machine is refused

    Examples:
      | asks                                     |
      | invites a member to "acme"               |
      | renames "acme"                           |
      | deletes "acme"                           |
      | creates a deploy token of "acme"         |
      | revokes a deploy token of "acme"         |
      | lists the deploy tokens of "acme"        |

  Scenario: a revoked deploy token is challenged on the next request to the instance that revoked it
    Given a deploy token of "acme"
    When the owner revokes the deploy token through one instance of the control plane
    Then the next request to that instance with the deploy token is challenged

  Scenario: a revoked deploy token is challenged by every instance of the control plane within a second
    Given a deploy token of "acme"
    And a control plane with 2 instances
    When the owner revokes the deploy token through one instance
    Then within a second a request to the other instance with the deploy token is challenged
    And no request with the deploy token is admitted again

  Scenario: a deploy token is told there is no project of another organization
    Given a deploy token of "acme"
    And a project "billing" of an organization "globex"
    When a machine holding the deploy token asks for the project "billing"
    Then the machine is told that there is no project "billing"

  Scenario: the control plane admits a deploy token from what it already holds
    Given a deploy token of "acme"
    When a machine sends a request to the control plane with the deploy token
    Then the control plane admits the request without reading its database or calling anything to decide

  Scenario: a deploy token created with no lifetime stated is challenged after 90 days
    Given the owner created a deploy token without stating a lifetime
    And nobody has revoked it
    When a machine sends a request with the deploy token 90 days after it was created
    Then the request is challenged

  Scenario: a deploy token created never to expire is still admitted after 90 days
    Given the owner created a deploy token that never expires
    When a machine sends a request with the deploy token 90 days after it was created
    Then the control plane admits the request as a member of "acme"
    And a list of the deploy tokens of "acme" says the deploy token never expires
