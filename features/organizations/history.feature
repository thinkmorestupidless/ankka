Feature: Who did what
  The control plane records who did each thing to a service or an organization, and when. A
  service's history names the actor of every apply, pause, resume, restart, expose and delete. What
  was recorded before actors were recorded is shown with no actor, and is never attributed to anyone.

  Scenario: a service's history names who applied it and who paused it, and when
    Given "ada" applied the service "cart"
    And "bob" then paused "cart"
    When the history of "cart" is read
    Then the history shows the apply by "ada" and the pause by "bob", each with when it was done

  Scenario: what was recorded before actors were recorded is shown with no actor
    Given a service "cart" whose history was recorded before actors were recorded
    When the history of "cart" is read
    Then every entry recorded then is shown with no actor
    And "cart" may be paused, resumed and applied as any other service

  Scenario Outline: a machine is named as the actor of what it did
    Given a machine holding <credential> applied the service "cart"
    When the history of "cart" is read
    Then the history names that machine as the actor of the apply

    Examples:
      | credential                                 |
      | a token from the installation's issuer     |
      | a deploy token                             |

  Scenario: an organization created for its owner records the platform administrator as the actor and the owner as its first owner
    Given the platform administrator "root" created the organization "acme" for the owner whose subject is "ada-123"
    When what the control plane recorded of the creation of "acme" is read
    Then it names "root" as the actor
    And it names "ada-123" as the first owner of "acme"
