Feature: Objects in Google Cloud Storage are kept against accident, and every version can be deleted
  A bucket in Google Cloud Storage keeps every object that is overwritten or deleted as a
  noncurrent version, and a deleted object can be recovered for as long as the installation says.
  Every version of an object can still be deleted, and the deletion is final once that time has
  passed. No bucket has a retention policy: the platform refuses no deletion on account of an
  object's age, and keeping a document for as long as a rule requires is the service's own to do.
  Every bucket is encrypted, with the installation's wrapping key when it names one, and is made in the
  location its project names, or else the installation's.

  Background:
    Given an installation whose object store is Google Cloud Storage

  Scenario: an object that is overwritten can be read as it was before
    Given a deployed service "kyc" that has kept the object "passport.pdf" in its bucket
    When "kyc" keeps the object "passport.pdf" again with other contents
    Then "kyc" reads the object "passport.pdf" back as it was before, as a noncurrent version

  Scenario: an object that is deleted can be read back as its noncurrent version
    Given a deployed service "kyc" that has kept the object "passport.pdf" in its bucket
    When "kyc" deletes the object "passport.pdf"
    Then "kyc" reads the object "passport.pdf" back as a noncurrent version

  Scenario: deleting every version of an object leaves none listed
    Given a deployed service "kyc" that has kept the object "passport.pdf" in its bucket, and overwritten it
    When "kyc" deletes every version of the object "passport.pdf"
    Then no version of the object "passport.pdf" is listed in the bucket of "kyc"
    And the deletion is final once the time the status says has passed

  Scenario: the status of a bucket says how long a deleted object can still be recovered
    Given a deployed service "kyc" with a bucket, on an installation that keeps a deleted object for "7 days"
    When a member reads the status of "kyc"
    Then the status says that a deleted object of "kyc" can still be recovered for "7 days"

  Scenario: no bucket is made with a retention policy
    Given a deployed service "kyc" with a bucket
    When a platform administrator reads the settings of the bucket of "kyc" in Google Cloud Storage
    Then the bucket of "kyc" has no retention policy
    And the platform offers no setting that gives a bucket one

  Scenario: a bucket is encrypted with the installation's key when the installation names one
    Given the installation names a wrapping key for its buckets
    And a descriptor for a service "kyc" that asks for a bucket
    When a member applies the descriptor
    Then the bucket of "kyc" is encrypted with the installation's wrapping key

  Scenario: a bucket is made in the location the installation names
    Given the installation names the location "europe-west2" for its buckets
    And a descriptor for a service "kyc" in the project "casino" that asks for a bucket, where "casino" names no location
    When a member applies the descriptor
    Then the bucket of "kyc" is in the location "europe-west2"
    And the status of "kyc" names the location "europe-west2"

  Scenario: a bucket is made in the location its project names, when the project names one
    Given the installation names the location "europe-west2" for its buckets
    And a member has named the location "europe-west6" for the project "casino"
    And a descriptor for a service "kyc" in the project "casino" that asks for a bucket
    When a member applies the descriptor
    Then the bucket of "kyc" is in the location "europe-west6"
    And the status of "kyc" names the location "europe-west6"
