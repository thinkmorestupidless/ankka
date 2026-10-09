Feature: The name of a bucket in Google Cloud Storage
  A bucket's name in Google Cloud Storage is shared with every other customer of Google's and may
  hold no dot, so it cannot be the project and the service joined by one, as in Garage. The cloud
  provider names the bucket from the installation's prefix, the project, the service and a digest
  of the two, and reports the name; nothing else derives it. Two services whose project and name
  would join to the same name without the dot are still given buckets of their own.

  Scenario: two services whose project and name would join to the same hyphenated name are given different buckets
    Given an installation whose object store is Google Cloud Storage
    And a deployed service "b" in the project "shop-a" with a bucket
    When a member applies a descriptor for the service "a-b" in the project "shop" that asks for a bucket
    Then the bucket of "a-b" is not the bucket of "b"
    And the status of each names its own bucket
