Feature: Objects on Garage survive the loss of a machine
  An installation that keeps its objects on Garage may run it on three machines, each object on all
  three, and may name a secondary store outside the cluster to which every bucket, the services'
  and the backup buckets alike, is copied again and again: an object deleted from Garage is deleted
  from the copy at the next one, so the copy never holds what the installation has erased. Losing
  one machine loses nothing. An installation may require that a project is not backed up until its
  latest base backup has a copy outside the failure domain. The local platform keeps Garage on one
  machine with no secondary store.

  Scenario: an object written to a Garage of three machines is held on every one of them
    Given an installation whose Garage runs on 3 machines
    And a deployed service "reports" with a bucket
    When "reports" keeps the object "march.pdf" in its bucket
    Then each of the 3 machines holds "march.pdf"

  Scenario: losing one machine of a Garage of three and its volume loses no object
    Given an installation whose Garage runs on 3 machines
    And a deployed service "reports" that has kept the object "march.pdf" in its bucket
    When one of the 3 machines and its volume are lost
    Then "reports" reads the object "march.pdf" back from its bucket

  Scenario: every bucket of the installation's Garage is copied to the secondary store, and the status says when
    Given an installation on Garage that names a secondary store
    And a deployed service "reports" that has kept the object "march.pdf" in its bucket
    And a project "shop" with a base backup in its backup bucket
    When the next copy to the secondary store completes
    Then the secondary store holds "march.pdf" in the bucket of "reports"
    And the secondary store holds the base backup of "shop" in the backup bucket of "shop"
    And the status of the installation says when the last copy completed

  Scenario: an installation that keeps its backups on Garage with no secondary store says that they share the cluster's failure domain
    Given an installation on Garage that names no secondary store
    And a project "shop" that is backed up
    When the status of the installation is read
    Then it says that the backups of the installation share the failure domain of the cluster

  Scenario: the local platform keeps Garage on one machine with no secondary store
    Given a local platform
    When what it installs is read
    Then it installs Garage on one machine
    And it names no secondary store

  Scenario: an object deleted from Garage is deleted from the secondary store at the next copy
    Given an installation on Garage that names a secondary store
    And the secondary store holds "march.pdf" from the bucket of "reports"
    And "reports" has since deleted the object "march.pdf" from its bucket
    When the next copy to the secondary store completes
    Then the secondary store no longer holds "march.pdf"
    And the copy reports that it deleted 1 object

  Scenario: with a copy outside the failure domain required, a project is not backed up until its latest base backup has one
    Given an installation on Garage that requires a copy outside its failure domain
    And a project "shop" that has taken a base backup since the last copy to the secondary store
    When the status of the project "shop" is read
    Then it does not say that "shop" is backed up
    And it says that the latest base backup of "shop" has no copy outside the failure domain
