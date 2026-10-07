Feature: A web-hosted service with a bucket
  A web-hosted service has no database, and may still ask for a bucket. The variables that say
  where the bucket is and how to reach it are given to its process and never to the proxy.

  Scenario: the variables of a bucket are given to the process of a web-hosted service and not to its proxy
    Given a descriptor for the web-hosted service "web" that asks for a bucket
    When a member applies the descriptor
    Then the process of "web" is given the variables "ANKKA_S3_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET", "ANKKA_S3_ACCESS_KEY" and "ANKKA_S3_SECRET_KEY"
    And the proxy of "web" is given none of them

  Scenario: a web-hosted service keeps an object in its bucket and reads it back
    Given a web-hosted service "web" deployed with a bucket
    When the process of "web" keeps the object "logo.png" in its bucket with what its variables say
    Then the process of "web" reads the object "logo.png" back from its bucket
