Feature: The console's shell
  Every page of the console is shown inside one shell: a rail marking the area a member is
  reading, a bar saying where they are and offering the page's primary operation, a panel listing
  what sits beside the page, the page itself, and an inspector holding every operation the page
  offers. On a narrow screen the shell collapses in reading order and hides nothing.

  Background:
    Given a service "cart" deployed in the project "checkout"
    And a member of "checkout"

  Scenario: a page is shown inside the shell, with every operation in its inspector
    When the member reads the page of "cart"
    Then the rail marks the area "services"
    And the rail offers the areas "organizations", "projects", "services", "members" and "deploy tokens"
    And the rail offers signing out
    And the bar says "checkout" then "cart"
    And the bar offers the primary operation of the page, which is applying a descriptor
    And the bar names the member
    And the inspector offers the operations pause, restart, expose and delete
    And every operation offered elsewhere on the page is one the inspector offers too

  Scenario: the rail opens the members and the deploy tokens of the organization the page is in
    Given "checkout" is a project of the organization "acme"
    And the member belongs to the organization "globex" too
    When the member reads the page of "cart" and opens the area "members"
    Then the member is shown the members of "acme"

  Scenario: the front page has no panel, and the organization's areas wait until one is opened
    Given the member belongs to the organizations "acme" and "globex"
    When the member reads the front page
    Then the page lists "acme" and "globex"
    And no panel is shown
    And the page takes the width the panel would have had
    And the rail offers the area "organizations"
    And the rail shows the areas "members" and "deploy tokens" as unavailable

  Scenario: the panel lists the project's services with their lifecycle and marks the one being read
    Given a service "inventory" deployed in the project "checkout" with 2 instances, of which 1 is ready
    When the member reads the page of "cart"
    Then the panel lists "cart" and "inventory", each with its lifecycle and how many of its instances are ready
    And the panel marks "cart" as the one being read

  Scenario: the destructive operation waits behind a disclosure at the inspector's foot
    Given the member is reading the page of "cart"
    When the member opens the disclosure at the foot of the inspector, without scripts
    Then the operation delete is offered in it
    And no other operation is behind it

  Scenario: on a narrow screen the shell collapses in reading order and hides nothing
    When the member reads the page of "cart" on a narrow screen
    Then the rail, the bar, the panel, the page and the inspector are shown in that order
    And nothing of the shell is hidden
    And the page does not scroll sideways

  Scenario: a project with more services than the panel shows at once keeps the one being read in view
    Given 40 services deployed in the project "checkout"
    When the member reads the page of the last of them by name
    Then the panel lists the services in name order
    And the panel scrolls
    And the one being read is in view

  Scenario: an organization's page lists its projects in the panel
    Given the organization "acme" with the projects "checkout" and "billing"
    When a member of "acme" reads the page of "acme"
    Then the panel lists "checkout" and "billing", each with how many services it has
