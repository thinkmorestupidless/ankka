Feature: Buckets named in the descriptor
  A descriptor names the buckets a service has beside the one it asks for with
  "provisionObjectStorage", each with a name the storage client reaches it by. A named bucket is
  made by the platform with an exposure, private, signed or public, or is a supplied bucket the
  user made and configured themselves, for which the platform makes nothing. The service's one
  storage credential reaches every bucket the platform made for it and no other service's. The
  platform never deletes a named bucket, and moves a service's buckets together.

  Scenario: a service is given every bucket its descriptor names
    Given a descriptor for a service "listings" that asks for a bucket and names the buckets "photos" as public, "exports" as signed and "documents" as private
    When a member applies the descriptor
    Then "listings" starts with the variables "ANKKA_S3_BUCKET_PHOTOS", "ANKKA_S3_BUCKET_EXPORTS" and "ANKKA_S3_BUCKET_DOCUMENTS" set
    And "listings" starts with the variable "ANKKA_S3_PUBLIC_URL_PHOTOS" set
    And "listings" has no variable "ANKKA_S3_PUBLIC_URL_EXPORTS" and no variable "ANKKA_S3_PUBLIC_URL_DOCUMENTS"

  Scenario: a service reaches a named bucket through the storage client
    Given a deployed service "listings" with a bucket and a named bucket "documents"
    When a handler of "listings" keeps the object "contracts/42.pdf" in the bucket "documents" through the storage client
    Then a handler of "listings" reads the object "contracts/42.pdf" back from the bucket "documents" through the storage client
    And the bucket of "listings" does not hold the object "contracts/42.pdf"

  Scenario: a bucket name the descriptor does not give is refused
    Given a deployed service "listings" with a bucket and a named bucket "documents"
    When a handler of "listings" asks the storage client for the bucket "invoices"
    Then the handler is refused
    And the refusal names the bucket "documents" as the one the descriptor gives

  Scenario: a browser reads an object in a public bucket without a signed URL
    Given a deployed service "listings" with a public bucket "photos"
    And a handler of "listings" has kept the object "listings/42/front.jpg" in the bucket "photos" through the storage client
    When a person on the internet sends a request to the public address of "listings/42/front.jpg" in the bucket "photos" without a signed URL
    Then the person is shown the object "listings/42/front.jpg"

  Scenario Outline: an object in a private bucket is not readable from the internet whatever the service's other buckets admit
    Given a deployed service "listings" with a bucket, a public bucket "photos" and a private bucket "documents"
    And a handler of "listings" has kept the object "contracts/42.pdf" in <bucket>
    When a person on the internet sends a request for the object "contracts/42.pdf" in <bucket> without a signed URL
    Then the object store refuses the request

    Examples:
      | bucket                  |
      | the bucket of "listings" |
      | the bucket "documents"   |

  Scenario: a service signs a URL for an object in a signed bucket
    Given a deployed service "listings" with a signed bucket "exports", whose descriptor names the origin "https://app.example"
    And a handler of "listings" has kept the object "march.pdf" in the bucket "exports"
    When a browser on the origin "https://app.example" sends a request to a signed URL that "listings" made through the storage client for reading "march.pdf" in the bucket "exports"
    Then the browser is shown the object "march.pdf"

  Scenario Outline: a signed URL or a public address is refused on a bucket whose exposure does not allow it
    Given a deployed service "listings" with a public bucket "photos", a signed bucket "exports" and a private bucket "documents"
    When a handler of "listings" asks the storage client for <what> in the bucket "<bucket>"
    Then the handler is refused
    And the refusal names the exposure of the bucket "<bucket>"

    Examples:
      | what                                         | bucket    |
      | a signed URL for reading "march.pdf"         | documents |
      | a signed URL for reading "march.pdf"         | photos    |
      | the public address of "listings/42/front.jpg" | exports   |
      | the public address of "listings/42/front.jpg" | documents |

  Scenario: the address of an object in a public bucket is answered without a request to the object store
    Given a deployed service "listings" with a public bucket "photos"
    And the object store of "listings" cannot be reached
    When a handler of "listings" asks the storage client for the public address of "listings/42/front.jpg" in the bucket "photos"
    Then the handler is told the public address
    And nothing was sent to the object store

  Scenario Outline: a service's code is the same whether or not a content delivery network stands in front of its public buckets
    Given a deployed service "listings" with a public bucket "photos", on an installation <front>
    And a handler of "listings" has kept the object "listings/42/front.jpg" in the bucket "photos"
    When a person on the internet sends a request to the public address that the storage client answered for "listings/42/front.jpg" in the bucket "photos"
    Then the person is shown the object "listings/42/front.jpg"
    And the code of "listings" names no address

    Examples:
      | front                                                      |
      | with a content delivery network in front of public buckets |
      | with no content delivery network                           |

  Scenario: a supplied bucket is reached through the storage client and the platform makes nothing for it
    Given an object store of the developer's own with a bucket "acme-archive" and a project secret "archive-store" holding its credential
    And a descriptor for a service "listings" that names the bucket "archive" as supplied from "acme-archive" with the project secret "archive-store"
    When a member applies the descriptor
    And a handler of "listings" keeps the object "2025/ledger.csv" in the bucket "archive" through the storage client
    Then the bucket "acme-archive" in the object store of the developer's own holds the object "2025/ledger.csv"
    And the platform made no bucket and no storage credential for "archive"
    And the status says that the bucket "archive" is supplied

  Scenario: the storage credential of a service reaches every bucket the platform made for it and no other service's
    Given a deployed service "listings" with a bucket and a named bucket "documents"
    And a deployed service "billing" with a named bucket "invoices"
    When "listings" uses its storage credential against the bucket "invoices" of "billing"
    Then the object store refuses the request
    And "listings" reaches its bucket and the bucket "documents" with the same storage credential

  Scenario: a descriptor that names two buckets alike is refused
    Given a descriptor for a service "listings" that names the bucket "photos" twice
    When a member applies the descriptor
    Then the descriptor is refused
    And the refusal names the bucket "photos" as named twice

  Scenario Outline: a descriptor whose bucket name cannot name a bucket is refused
    Given a descriptor for a service "listings" that names a bucket "<name>"
    When a member applies the descriptor
    Then the descriptor is refused
    And the refusal names the rule for bucket names

    Examples:
      | name                              |
      | Photos                            |
      | listing photos                    |
      | photos.2025                       |
      | a-name-longer-than-the-limit-allows |

  Scenario: the status of a service names each of its buckets, its exposure and its address
    Given a deployed service "listings" with a bucket, a public bucket "photos", a signed bucket "exports" and a supplied bucket "archive"
    When a member reads the status of "listings"
    Then the status names the bucket of "listings" and the buckets "photos", "exports" and "archive"
    And the status says that "photos" is public and names its public address
    And the status says that "exports" is signed and names its address on the internet
    And the status says that "archive" is supplied

  Scenario: deleting a service keeps every bucket the platform made and its objects
    Given a deployed service "listings" with a bucket and a public bucket "photos"
    And a handler of "listings" has kept the object "listings/42/front.jpg" in the bucket "photos"
    When a member deletes "listings"
    Then the bucket "photos" of "listings" and the object "listings/42/front.jpg" are kept

  Scenario: a service that stops naming a bucket keeps it and loses its variables
    Given a deployed service "listings" with a bucket and a public bucket "photos"
    And a handler of "listings" has kept the object "listings/42/front.jpg" in the bucket "photos"
    When a member applies a descriptor for "listings" that no longer names the bucket "photos"
    Then the bucket "photos" of "listings" and the object "listings/42/front.jpg" are kept
    And "listings" has no variable "ANKKA_S3_BUCKET_PHOTOS"
    And the status says that the bucket "photos" is kept and no longer named

  Scenario: a service's buckets are moved together
    Given a deployed service "listings" with a bucket and a public bucket "photos", both in Garage
    When a member moves the buckets of "listings" to Google Cloud Storage
    Then the bucket of "listings" and the bucket "photos" are in Google Cloud Storage with their objects
    And the status names the public address of "photos" in Google Cloud Storage
