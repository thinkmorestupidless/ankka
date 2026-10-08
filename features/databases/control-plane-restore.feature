Feature: Restoring the control plane's database
  The control plane's own database is restored by a platform administrator, as the documentation
  says, since there is no control plane to ask. The restore ends by writing the restore marker, and
  the control plane that starts on the restored database holds its projection: it changes nothing
  in the cluster, and lists every service, every declared topic and every project that differs from
  what it recorded, until a platform administrator releases it. Before it can be released it brings
  its erasure log up to date from the copy in its bucket, so that an erasure filed after the restore
  point is not forgotten.

  Background:
    Given an installation with a backup target
    And a platform administrator "root"

  Scenario: a platform administrator restores the database of the control plane and the control plane starts held
    When "root" restores the database of the control plane to a moment, as the documentation says
    Then the restore ends by writing the restore marker
    And the control plane starts on the restored database with its projection held

  Scenario: a held control plane lists what differs from what it recorded and changes none of it
    Given a service "cart" applied at generation 2 after the restore point, running the image "cart:2"
    And a project "lab" created after the restore point
    And the database of the control plane restored to the restore point
    When the control plane starts with its projection held
    Then it lists "cart" as a service whose recorded descriptor differs from what runs in the cluster
    And it lists every declared topic that differs from what the broker holds
    And it lists the project "lab" as one the cluster holds and its database does not know
    And it changes none of them
    And "cart" keeps running the image "cart:2"

  Scenario: a held control plane brings its erasure log up to date from the bucket before it can be released
    Given an erasure filed after the restore point
    And the database of the control plane restored to the restore point
    When the control plane starts with its projection held
    Then its erasure log is brought up to date from the copy in its bucket before "root" can release it
    And the listing of what differs says that the erasure log was brought up to date, and how many erasures it gained

  Scenario: releasing the projection resumes it, removes the restore marker and is recorded
    Given the control plane started with its projection held
    When "root" releases the projection
    Then the control plane makes the cluster what it recorded again
    And the restore marker is removed
    And the control plane records that "root" released the projection
