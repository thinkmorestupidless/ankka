Feature: A bucket in Google Cloud Storage for a service that asks for one
  An installation may keep its buckets in Google Cloud Storage instead of Garage. The operator
  asks the cloud provider for a bucket, and the cloud provider makes it, names it and reports it; the operator
  reads what the cloud provider reports and never reaches Google Cloud itself. A service that asks for a
  bucket is given one the same way on either object store, with the same variables, and keeps and
  reads objects with the same client. Which object store the bucket is in, and where, is read from
  the status, as the cloud provider reported it.

  Background:
    Given an installation whose object store is Google Cloud Storage

  Scenario: a service that asks for a bucket on an installation that keeps objects in Google Cloud Storage is given one
    Given a descriptor for a service "kyc" that asks for a bucket
    When a member applies the descriptor
    Then "kyc" starts with the variables "ANKKA_S3_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET", "ANKKA_S3_ACCESS_KEY" and "ANKKA_S3_SECRET_KEY" set
    And the variable "ANKKA_S3_BUCKET" names a bucket the cloud provider made for "kyc" in Google Cloud Storage
    And the descriptor is the one "kyc" would have on an installation whose object store is Garage

  Scenario: a service keeps an object in its Google Cloud Storage bucket and reads it back with the client it used against Garage
    Given a deployed service "kyc" with a bucket
    When "kyc" keeps the object "passport.pdf" in its bucket with what its variables say, through the client it used against Garage
    Then "kyc" reads the object "passport.pdf" back from its bucket

  Scenario: the status of a service names its bucket, its object store and its location as the cloud provider reported them
    Given a deployed service "kyc" with a bucket
    When a member reads the status of "kyc"
    Then the status says that "kyc" has a bucket in Google Cloud Storage, and names it as the cloud provider reported it
    And the status names the location of the bucket of "kyc" as the cloud provider reported it

  Scenario: a bucket whose name another Google customer holds is reported as failed and not used
    Given a bucket named as the cloud provider would name the bucket of "kyc", held by someone outside the installation
    And a descriptor for a service "kyc" that asks for a bucket
    When a member applies the descriptor
    Then the status says that the bucket of "kyc" is "Failed", naming the bucket
    And nothing of "kyc" is granted on that bucket
    And no instance of "kyc" starts

  Scenario: a bucket Google Cloud Storage cannot make yet is reported as still being made
    Given Google Cloud Storage cannot be reached at present
    And a descriptor for a service "kyc" that asks for a bucket
    When a member applies the descriptor
    Then the status says that the bucket of "kyc" is still being made
    And the status of "kyc" is not "Failed"
