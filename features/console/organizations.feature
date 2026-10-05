Feature: Organizations and projects through the console
  Through the console a person sees the organizations they belong to, creates one and becomes its
  owner, renames and deletes one they own, and creates, renames and deletes projects in it. Every
  refusal the control plane gives is shown as what it is, with its reason.

  Scenario: a member is shown the organizations they belong to and no other
    Given a person who is a member of the organizations "acme" and "globex"
    And an organization "initech" the person does not belong to
    When the person signs in to the console
    Then the person is shown "acme" and "globex", each with its name, its id and whether the person is an owner of it
    And the person is shown nothing of "initech"

  Scenario: a platform administrator is shown every organization of the installation
    Given a platform administrator who is a member of the organization "acme" only
    And an organization "globex"
    When the platform administrator signs in to the console
    Then the platform administrator is shown "acme" and "globex"
    And "globex" is marked as shown to a platform administrator rather than to a member

  Scenario: a person who creates an organization is its first owner and is taken to it
    Given a person signed in to the console
    When the person creates the organization "acme" named "Acme"
    Then the organization "acme" exists with the person as its owner
    And the person is shown "acme" at once, whether or not a list of organizations shows it yet

  Scenario: an owner renames an organization
    Given an owner of the organization "acme"
    When the owner renames "acme" to "Acme Corporation"
    Then "acme" is shown as "Acme Corporation" wherever it is shown

  Scenario: a member who is not an owner cannot rename an organization
    Given a member of the organization "acme" who is not an owner
    When the member renames "acme" anyway
    Then the member is refused, with the control plane's reason
    And the member was not offered the rename

  Scenario: an owner deletes an organization with no projects
    Given an owner of the organization "acme", which has no projects
    When the owner deletes "acme"
    Then "acme" is no longer among the organizations the owner is shown

  Scenario: an organization with projects cannot be deleted
    Given an owner of the organization "acme", which has the project "shop"
    When the owner deletes "acme"
    Then the owner is refused
    And the refusal says that "acme" still has projects

  Scenario Outline: the id of a deleted organization or project cannot be used again
    Given the <kind> "acme" was deleted
    When a person signed in to the console creates the <kind> "acme"
    Then the person is refused
    And the refusal says that the id "acme" cannot be used again

    Examples:
      | kind         |
      | organization |
      | project      |

  Scenario Outline: a member creates, renames and deletes projects
    Given a member of the organization "acme" <state>
    When the member <action>
    Then <outcome>

    Examples:
      | state                                       | action                                  | outcome                                          |
      | with no project "shop"                      | creates the project "shop" named "Shop" | the member is shown the project "shop" in "acme" |
      | with the project "shop"                     | renames "shop" to "Storefront"          | the member is shown "shop" as "Storefront"       |
      | with the project "shop", which has no services | deletes "shop"                       | "shop" is no longer among the projects of "acme" |

  Scenario: a project with services cannot be deleted
    Given a member of the organization "acme" with the project "shop", which has the service "cart"
    When the member deletes "shop"
    Then the member is refused
    And the refusal says that "shop" still has services

  Scenario Outline: where only a platform administrator creates organizations a member is shown where to sign up
    Given an installation where only a platform administrator creates organizations, <signup>
    When a member who is not a platform administrator creates the organization "acme"
    Then the member is refused
    And <shown>

    Examples:
      | signup                                            | shown                                                        |
      | with the sign-up address "https://example.test/join" | the refusal shows the sign-up address "https://example.test/join" |
      | with no sign-up address                           | the refusal shows no sign-up address                         |
