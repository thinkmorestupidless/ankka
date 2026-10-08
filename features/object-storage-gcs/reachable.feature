Feature: A bucket in Google Cloud Storage reachable from a browser
  A bucket in Google Cloud Storage is reachable from the internet by anyone who holds a signed
  URL, whether or not its descriptor asked, and by nobody else: nothing in it is ever read without
  one or by an identity granted on it. A descriptor that asks for its bucket to be reachable from
  the internet is told the address of its bucket on the internet, and names the origins a browser
  may send from, which are not in general the service's own hostname. The platform sets the bucket
  to admit those origins; the service sets nothing on its bucket.

  Background:
    Given an installation whose object store is Google Cloud Storage

  Scenario: a service whose bucket is reachable from the internet is told the address of its bucket on the internet
    Given a descriptor for a service "kyc" that asks for a bucket reachable from the internet
    When a member applies the descriptor
    Then "kyc" starts with the variable "ANKKA_S3_PUBLIC_ENDPOINT" set, naming its bucket in Google Cloud Storage
    And the status shows the address of the bucket of "kyc" on the internet

  Scenario: a browser on an origin the descriptor names keeps an object through a signed URL
    Given a deployed service "kyc" whose bucket is reachable from the internet, whose descriptor names the origin "https://play.example"
    When a browser on the origin "https://play.example" sends the object "selfie.jpg" to a signed URL that "kyc" made for keeping "selfie.jpg"
    Then "kyc" reads the object "selfie.jpg" back from its bucket

  Scenario Outline: a browser on an origin the descriptor does not name cannot send an object to the bucket
    Given a deployed service "kyc" whose bucket is reachable from the internet, whose descriptor names the origin "https://play.example"
    When a browser on <origin> sends the object "selfie.jpg" to a signed URL that "kyc" made for keeping "selfie.jpg"
    Then the browser is refused
    And the bucket of "kyc" does not hold the object "selfie.jpg"

    Examples:
      | origin                               |
      | the hostname of "kyc"                |
      | the origin "https://elsewhere.example" |

  Scenario Outline: a request without a signed URL is refused by every bucket
    Given a deployed service "kyc" <state>
    And "kyc" has kept the object "passport.pdf" in its bucket
    When a person on the internet sends a request for the object "passport.pdf" without a signed URL
    Then Google Cloud Storage refuses the request

    Examples:
      | state                                                                                        |
      | whose bucket is reachable from the internet                                                  |
      | with a bucket, whose descriptor does not ask that the bucket be reachable from the internet  |
