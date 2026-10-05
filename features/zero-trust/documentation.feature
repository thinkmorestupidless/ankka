Feature: The documentation of what the platform protects
  Someone deciding how to protect a service learns from the documentation which connections are
  proven, what a calling workload is and how an ACL names one, what the network refuses, and what an
  installation must provide for any of it to hold. What the platform still does not protect is said
  plainly, and nothing it now protects is listed as missing.

  Scenario: the limitations list what is still not protected and none of what now is
    Given the published documentation
    When a reader reads the limitations of networking and security
    Then the reader is not told that connections inside the cluster are in the clear, that the platform names no calling workload, or that a database is reached with a password
    And the reader is told what the platform still does not protect

  Scenario: the documentation of installing in a cluster says the network must enforce what the platform asks of it
    Given the published documentation
    When a reader reads what installing the platform in a cluster asks of the cluster's network
    Then the documentation says that the cluster's network must refuse what the platform asks it to refuse
    And the documentation says how to check that it does, and what an installation is left with if it does not

  Scenario: the ACLs that name a calling workload mean the same in every language
    Given the published documentation
    When a reader reads about ACLs for "Scala", "Python" and "TypeScript"
    Then the documentation of each language names the gateway, a named service and the service itself in an ACL in the same way
