Feature: Restoring a project to a moment and switching a service to it
  An owner restores a project to a moment inside its retention window. The restore is a new project
  database beside the current one, made from the latest base backup before the moment and the
  archive up to it; the current project database is not changed. The platform checks the restore and
  reports it per service. Only a switch moves a service onto it, one service at a time, at the
  service's next rolling update; the project database a service leaves is kept, and switching back
  is the same action. A restore is not a roll back: a roll back runs an earlier descriptor against
  the data there is, a restore runs the descriptor there is against earlier data.

  Background:
    Given a project "shop" that is backed up
    And a deployed service "wallet" in the project "shop" with a provisioned database
    And a deployed service "rewards" in the project "shop" with a provisioned database
    And an owner of the organization "shop" is in

  Scenario: a restore makes a new project database beside the current one and changes nothing in the current one
    When the owner restores "shop" to a moment inside its retention window
    Then a restore of "shop" is made beside the project database of "shop", from the latest base backup before that moment and the archive up to it
    And the project database of "shop" is unchanged

  Scenario Outline: a restore to a moment that cannot be restored to is refused with the moments that can
    When the owner restores "shop" to a moment <moment>
    Then the owner is refused
    And the refusal names the earliest and the latest moments "shop" can be restored to

    Examples:
      | moment                       |
      | before its retention window  |
      | in the future                |

  Scenario: a completed restore reports the moment it reached and what each service's database holds
    Given a completed restore of "shop"
    When the owner reads the restore
    Then the restore reports the moment it reached
    And for each service of "shop" the restore reports that its database is present
    And for each service of "shop" the restore reports the number of rows in its journal, its states, its read positions and its timers
    And for each service of "shop" the restore reports the highest sequence number its journal holds

  Scenario: switching one service to a restore moves that service and no other, and is recorded
    Given a completed restore of "shop"
    When the owner switches "rewards" to the restore
    Then "rewards" is on the restore after its next rolling update
    And "wallet" is on the project database it was on
    And the history of "shop" shows the switch of "rewards" by the owner
    And the project database "rewards" left is kept and listed on "shop"

  Scenario: a switched service is switched back to the project database it left, with every write that project database had
    Given "rewards" switched to a restore of "shop"
    And the project database "rewards" left holds an event "rewards" recorded before the switch
    When the owner switches "rewards" back to the project database it left
    Then "rewards" is on that project database after its next rolling update
    And "rewards" still holds the event

  Scenario Outline: a member who is not an owner can neither restore nor switch
    Given a member of the organization "shop" who is not an owner
    When the member <asks>
    Then the member is refused

    Examples:
      | asks                                      |
      | restores "shop" to a moment               |
      | switches "rewards" to a restore of "shop" |

  Scenario: a second restore of a project is refused while one is in progress
    Given a restore of "shop" that is in progress
    When the owner restores "shop" to another moment
    Then the owner is refused
    And the refusal says that a restore of "shop" is in progress

  Scenario: a restore a service was switched to is archived as a line of history of its own
    Given "rewards" switched to a restore of "shop"
    When the project database "rewards" is on next takes a base backup
    Then the restore is archived as a line of history of its own, beside the line of history of the project database "rewards" left
    And every earlier line of history of "shop" can still be restored within its retention window

  Scenario: the status of a project whose services are on two project databases names each service's
    Given "rewards" switched to a restore of "shop" and "wallet" not
    When the owner reads the status of the project "shop"
    Then the status names the project database each service is on
    And the status says that the services of "shop" are on two project databases

  Scenario: a project database every service has left is listed as left, and the platform never removes it
    Given "rewards" and "wallet" both switched to a restore of "shop"
    When the owner reads the status of the project "shop"
    Then the status lists the project database both left as left, with the time the last service left it
    And the platform never removes it

  Scenario: a restore no service was switched to is listed with its age, and only a platform administrator removes it by hand
    Given a restore of "shop" that no service was switched to
    When the owner reads the status of the project "shop"
    Then the status lists the restore with its age
    And the platform does not remove it
    And the documentation says how a platform administrator removes it by hand
