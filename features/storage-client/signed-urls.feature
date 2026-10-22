Feature: Signed URLs made by the storage client
  A service whose bucket is reachable from the internet asks the storage client for a signed URL
  for reading one object, or for keeping one, for a span it chooses. The client signs against the
  address of the bucket on the internet without the service naming it, and makes the signed URL
  by computation alone. A service whose bucket is not reachable from the internet is refused one.

  Background:
    Given a deployed service "listings" whose bucket is reachable from the internet

  Scenario: a service makes a signed URL for reading an object through the storage client and a browser reads the object
    Given a handler of "listings" has kept the object "exports/march.pdf"
    When a browser sends a request to a signed URL that "listings" made through the storage client for reading "exports/march.pdf"
    Then the browser is shown the object "exports/march.pdf"

  Scenario: a service makes a signed URL for keeping an object through the storage client and a browser keeps the object
    When a browser sends the object "uploads/passport.pdf" to a signed URL that "listings" made through the storage client for keeping "uploads/passport.pdf"
    Then a handler of "listings" reads the object "uploads/passport.pdf" back through the storage client

  Scenario: a signed URL expires when the service said it would
    Given a handler of "listings" has kept the object "exports/march.pdf"
    And "listings" made a signed URL through the storage client for reading "exports/march.pdf" for "5 minutes"
    When a browser sends a request to the signed URL after "6 minutes"
    Then the object store refuses the request

  Scenario: a signed URL for a span longer than the bound is refused
    When a handler of "listings" asks the storage client for a signed URL for reading "exports/march.pdf" for "8 days"
    Then the handler is refused
    And the refusal names the bound of "7 days"

  Scenario: a service whose bucket is not reachable from the internet is refused a signed URL with the reason
    Given a deployed service "catalogue" with a bucket, whose descriptor does not ask that the bucket be reachable from the internet
    When a handler of "catalogue" asks the storage client for a signed URL for reading "exports/march.pdf"
    Then the handler is refused
    And the refusal says that the bucket is not reachable from the internet and names "exposeObjectStorage"

  Scenario: a signed URL is made without a request to the object store
    Given the object store of "listings" cannot be reached
    When a handler of "listings" asks the storage client for a signed URL for reading "exports/march.pdf"
    Then the handler is told a signed URL
    And nothing was sent to the object store
