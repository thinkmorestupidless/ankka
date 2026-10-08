Feature: Every project database is backed up, and a failure is seen
  An installation names a backup target. From then on every project database, and the database of
  the control plane, archives every write as it is made and takes a base backup every day, with no
  change to any descriptor. A project's status says when its last base backup completed, how far
  back it can be restored and how far behind its archive is. When archiving stops, the status and a
  metric say so, before anyone needs the backup. A database a service declares of its own is the
  service's to back up, not the platform's.

  Scenario: the first service of a project is deployed and its project database is backed up from then on
    Given an installation with a backup target
    And a project "shop" with no services
    When a member applies a descriptor for the service "cart" in the project "shop" that says nothing of a database
    Then the project database of "shop" archives every write as it is made
    And the project database of "shop" completes a first base backup
    And the status of the project "shop" says when its last base backup completed and the earliest moment it can be restored to

  Scenario: a project that existed before the installation had a backup target is backed up without a redeploy
    Given an installation with no backup target
    And a deployed service "cart" in the project "shop" with a provisioned database
    And "cart" has recorded an event
    When the installation names a backup target
    Then the project database of "shop" begins archiving and takes a base backup
    And no instance of "cart" is replaced
    And "cart" still holds the event

  Scenario: a backup target that refuses writes is reported on the status and as a metric within 5 minutes
    Given an installation with a backup target
    And a project "shop" that is backed up
    When the backup target refuses writes
    Then within 5 minutes the status of the project "shop" says that its backups are failing, and why
    And the metric the platform exports for the backups of "shop" reports the failure

  Scenario: the database of the control plane is backed up as a project database is
    Given an installation with a backup target
    When the status of the installation is read
    Then the database of the control plane archives every write as it is made, into a backup bucket of its own
    And the database of the control plane has a base backup
    And the status of the installation says when the last base backup of the database of the control plane completed

  Scenario: a database a service declares of its own is not backed up by the platform
    Given an installation with a backup target
    And a deployed service "ledger" in the project "shop" that declares a database of its own
    When the project database of "shop" takes a base backup
    Then the database of "ledger" is no part of it
    And the status of "ledger" says that the platform does not back up a database the service declares

  Scenario: an installation with no backup target deploys as before and says that nothing is backed up
    Given an installation with no backup target
    And a project "shop" with no services
    When a member applies a descriptor for the service "cart" in the project "shop"
    Then "cart" is ready
    And the status of the installation says that nothing is backed up
    And the status of the project "shop" says that nothing is backed up
