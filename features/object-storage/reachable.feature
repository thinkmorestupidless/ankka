Feature: A bucket reachable from the internet
  A bucket is reached only from inside the installation until its descriptor asks otherwise. A
  descriptor that asks for a bucket may also ask that the bucket be reachable from the internet.
  The platform then tells the service the address of its bucket on the internet, and the service
  makes signed URLs to its objects with it. The descriptor names the origins a browser may send
  from, which need not be the service's own hostname; the platform sets the bucket to admit them,
  and the service sets nothing on its bucket. The object store answers a request from the internet
  only for a signed URL: nothing in a bucket is ever read without one.

  Scenario: a service whose bucket is reachable from the internet is told the address of its bucket on the internet
    Given a descriptor for a service "reports" that asks for a bucket reachable from the internet
    When a member applies the descriptor
    Then "reports" starts with the variable "ANKKA_S3_PUBLIC_ENDPOINT" set
    And the status shows the address of the bucket of "reports" on the internet

  Scenario: a browser reads an object through a signed URL
    Given a deployed service "reports" whose bucket is reachable from the internet
    And "reports" has kept the object "march.pdf" in its bucket
    When a browser sends a request to a signed URL that "reports" made for reading "march.pdf"
    Then the browser is shown the object "march.pdf"

  Scenario: a browser on an origin the descriptor names keeps an object through a signed URL without the service setting a rule on its bucket
    Given a deployed service "reports" whose bucket is reachable from the internet, whose descriptor names the origin "https://app.example"
    And "reports" has set nothing on its bucket
    When a browser on the origin "https://app.example" sends the object "upload.png" to a signed URL that "reports" made for keeping "upload.png"
    Then "reports" reads the object "upload.png" back from its bucket

  Scenario: a bucket is not reachable from the internet until its descriptor asks
    Given a deployed service "reports" with a bucket, whose descriptor does not ask that the bucket be reachable from the internet
    When a person on the internet sends a request for the object "march.pdf" in the bucket of "reports"
    Then the request does not reach the object store
    And "reports" has no variable "ANKKA_S3_PUBLIC_ENDPOINT"

  Scenario: a request from the internet without a signed URL is refused
    Given a deployed service "reports" whose bucket is reachable from the internet
    And "reports" has kept the object "march.pdf" in its bucket
    When a person on the internet sends a request for the object "march.pdf" without a signed URL
    Then the object store refuses the request

  Scenario: a signed URL stops working when the bucket is no longer reachable from the internet
    Given a deployed service "reports" whose bucket is reachable from the internet
    And a signed URL that "reports" made for reading "march.pdf"
    When a member applies the descriptor of "reports" without asking that the bucket be reachable from the internet
    Then a request a browser sends to the signed URL does not reach the object store

  Scenario Outline: only a bucket the platform made can be made reachable from the internet
    Given a descriptor for a service "reports" that asks that its bucket be reachable from the internet and <state>
    When a member applies the descriptor
    Then the member is refused
    And the refusal says that only a bucket the platform made can be reachable from the internet

    Examples:
      | state                                   |
      | asks for no bucket                      |
      | gives the variable "ANKKA_S3_ENDPOINT"  |
