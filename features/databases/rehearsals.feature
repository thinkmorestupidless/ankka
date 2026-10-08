Feature: A restore is rehearsed without touching the project
  Any member rehearses a restore of a project to a moment. The platform restores into a project
  database made for the rehearsal, apart from the project's own, which no service is switched to;
  checks it as a restore is checked; records how long it took; and removes it. The report is kept,
  so the organization can show that recovery is tested, when, and how long it takes. The operator
  may remove a project database made for a rehearsal and no other, and one it failed to remove is
  removed when its time to live passes.

  Background:
    Given a project "shop" that is backed up
    And a member of the organization "shop" is in

  Scenario: a rehearsal restores into a project database of its own, checks it, times it and removes it
    When the member rehearses a restore of "shop" to a moment
    Then a project database is made for the rehearsal, apart from the project database of "shop"
    And no service is switched to it
    And it is checked as a restore is checked
    And the rehearsal records how long it took
    And the project database made for the rehearsal is then removed

  Scenario Outline: a rehearsal's report is kept on the project and listed
    Given a rehearsal of "shop" that <ended>
    When the member lists the rehearsals of "shop"
    Then the rehearsal is listed with who asked for it, when, the moment, its outcome and how long it took

    Examples:
      | ended     |
      | completed |
      | failed    |

  Scenario: a project set to rehearse every day rehearses every day, and a failed rehearsal is reported as a backup failure is
    When the member sets "shop" to rehearse a restore every day
    Then a rehearsal of "shop" runs every day
    And a rehearsal that fails is reported on the status of the project "shop" and as a metric, as a backup failure is

  Scenario: a rehearsal changes nothing of the project's services, project database or backups
    Given a deployed service "wallet" in the project "shop" that has recorded an event
    When a rehearsal of "shop" runs
    Then no instance of "wallet" is replaced
    And the project database of "shop" is unchanged
    And the backups of "shop" are unchanged

  Scenario: a rehearsal's project database the platform failed to remove is removed when its time to live passes
    Given a project database made for a rehearsal of "shop" that the platform failed to remove when the rehearsal ended
    When its time to live passes
    Then it is removed
    And the status of the project "shop" reported that it was not removed when the rehearsal ended

  Scenario: the operator may remove a project database made for a rehearsal and no other
    Given the operator
    When what the operator may do in the cluster is read
    Then it may remove a project database made for a rehearsal
    And it may remove no other project database
