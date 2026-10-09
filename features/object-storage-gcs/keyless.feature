Feature: A service reaches its bucket in Google Cloud Storage with no storage credential
  On an installation whose object store is Google Cloud Storage, a service's workload identity is
  its cloud identity, so a service written with Google's own client reaches its bucket, and no
  other, holding no storage credential at all. A descriptor may decline the storage credential:
  such a service is given none and none is issued for it, and on an installation whose object
  store is Garage it is refused, since there is no other way to reach a bucket there.

  Scenario: a service reaches its bucket through its workload identity with no storage credential
    Given an installation whose object store is Google Cloud Storage
    And a deployed service "kyc" with a bucket, written with Google's own client and given no storage credential
    When "kyc" keeps the object "passport.pdf" in its bucket as its workload identity
    Then "kyc" reads the object "passport.pdf" back from its bucket

  Scenario: a service reaching Google Cloud Storage through its workload identity is refused by another service's bucket
    Given an installation whose object store is Google Cloud Storage
    And a deployed service "kyc" with a bucket, written with Google's own client and given no storage credential
    And a deployed service "ledger" with a bucket
    When "kyc" reads the bucket of "ledger" as its workload identity
    Then Google Cloud Storage refuses "kyc"

  Scenario: a service that declines a storage credential is given none
    Given an installation whose object store is Google Cloud Storage
    And a descriptor for a service "kyc" that asks for a bucket and declines a storage credential
    When a member applies the descriptor
    Then "kyc" starts with no variable "ANKKA_S3_ACCESS_KEY" and no variable "ANKKA_S3_SECRET_KEY"
    And no storage credential is issued for "kyc"

  Scenario: a descriptor that declines a storage credential is refused on an installation whose object store is Garage
    Given an installation whose object store is Garage
    And a descriptor for a service "kyc" that asks for a bucket and declines a storage credential
    When a member applies the descriptor
    Then the member is refused
    And the refusal says that a bucket in Garage is reached only with a storage credential
