Feature: A bucket made by the installation's cloud provider
  On an installation whose object store is its cloud account's, the operator makes no bucket
  itself. It writes a bucket request and a bucket credential request, and the cloud provider makes
  the bucket in the cloud account, makes a cloud identity for the service, grants it that bucket
  and no other, makes a storage credential once, and writes each fulfilment back. The operator
  reads the fulfilments, gives the service its variables, and reports the bucket as it reports one
  it made itself. The developer's program is the one that runs on the installation's own object
  store.

  Background:
    Given an installation whose cloud provider is "gcp"
    And the object store of the installation is its cloud account's

  Scenario: a descriptor that asks for a bucket becomes a bucket request and a bucket credential request
    Given a descriptor for a service "reports" in the project "shop" that asks for a bucket
    When a member applies the descriptor
    Then the operator writes an identity request for "reports"
    And the operator writes a bucket request for "reports" with the purpose "service", naming the project "shop", the location of "shop" or else the installation's, and what the descriptor asks of the bucket
    And the operator writes a bucket credential request for "reports" naming that bucket, the cloud identity of "reports" and the secret "reports-storage"

  Scenario: a service is given its bucket when the cloud provider fulfils the requests
    Given a bucket request and a bucket credential request for "reports"
    When the cloud provider fulfils them
    Then each fulfilment is acknowledged and says "Ready"
    And the fulfilment of the bucket request names the bucket as the cloud provider made it
    And the fulfilment of the bucket credential request names the secret "reports-storage"
    And "reports" starts with the variables "ANKKA_S3_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET", "ANKKA_S3_ACCESS_KEY" and "ANKKA_S3_SECRET_KEY" set from the fulfilments and the secret
    And the status says that "reports" has a bucket, and names the bucket as the cloud provider made it

  Scenario Outline: a bucket the cloud provider cannot make fails the service with the reason the cloud provider gave
    Given a bucket request for "reports" that the cloud provider cannot fulfil because <why>
    When the cloud provider says "Failed" and gives its reason
    Then the status of "reports" is "Failed" with the reason the cloud provider gave, word for word
    And "reports" starts with no variable whose name starts with "ANKKA_S3_"

    Examples:
      | why                                                |
      | the name of the bucket is taken in another account |
      | the location is refused                            |

  Scenario: a service deleted and applied again under the same name is given the bucket it had
    Given a deployed service "reports" with a bucket the cloud provider made
    And "reports" has since been deleted
    When a member applies the descriptor for "reports" again
    Then the operator writes the same identity request, the same bucket request and the same bucket credential request again
    And the cloud provider finds the bucket it made before and says "Recovered"
    And the cloud provider offers a storage credential, is told that one is already there, and names the secret that holds it
    And the status says that "reports" was given the bucket it had before
