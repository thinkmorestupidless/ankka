Feature: A host building on the console
  The console is published for hosts to build on: a host serves what the console shows under a
  path of its own, inside its own look, with its own sign-in and its own way of keeping sign-ins,
  and adds its own things beside the console's, without changing the console.

  Scenario: a host serves the console under a path of its own, inside its own look
    Given a host with a look of its own
    When the host serves the console under the path "/platform"
    Then everything the console shows of organizations, projects, services, members and deploy tokens is shown inside the host's look under "/platform"
    And everywhere the console sends the person from there stays under "/platform"

  Scenario: the console calls the control plane as the person the host signed in
    Given a host that signs people in itself and gives the console a person's token
    When a person the host signed in uses the console
    Then the console calls the control plane as that person
    And the console never asks the person to sign in

  Scenario Outline: the console keeps sign-ins the way the host does
    Given a host that keeps sign-ins its own way, rather than in the browser
    When a person <action>
    Then the sign-in is <outcome> where the host keeps sign-ins

    Examples:
      | action                                    | outcome   |
      | signs in through the console              | kept      |
      | whose sign-in needs renewing asks for anything | renewed |
      | signs out of the console                  | removed   |

  Scenario Outline: what a host adds is shown where it declared it, with what it declared it for
    Given a host that adds <addition>
    When a member reads the organization "acme" through the host
    Then the member is shown the host's addition where the host declared it
    And the host's addition is given "acme"

    Examples:
      | addition                                          |
      | something shown with each organization            |
      | a choice of its own beside creating an organization      |
      | something of its own beside the console's         |

  Scenario: what a host hides is not offered
    Given a host that hides deleting an organization
    And an owner of the organization "acme"
    When the owner reads "acme" through the host
    Then the owner is not offered the deletion of "acme"

  Scenario: what a host hides is still refused where the control plane refuses it
    Given a host that hides deleting an organization
    And a member of the organization "acme" who is not an owner
    When the member deletes "acme" through the host anyway
    Then the member is refused, with the control plane's reason

  Scenario: the console reads every answer of the control plane as the control plane wrote it
    Given every kind of answer the control plane gives, as the control plane itself writes it
    When the console reads each of them
    Then the console reads the same values the control plane wrote
