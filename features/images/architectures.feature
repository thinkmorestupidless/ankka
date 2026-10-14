Feature: Images for both architectures
  Every image of the platform is published for both architectures under one tag, so a cluster whose
  nodes are arm64 pulls and starts the platform as a cluster of amd64 nodes does. A release is refused
  rather than published for one architecture. The developer's own image is the developer's: the
  platform says which architecture an image lacks when an instance cannot start from it.

  Scenario: a release publishes every image of the platform for both architectures under one tag
    Given a release of the platform
    When the release publishes its images
    Then every image of the platform is published under the release's tag for the architectures "amd64" and "arm64"
    And no tag or image name carries an architecture

  Scenario: a cluster of arm64 nodes starts every image of the platform
    Given a cluster whose nodes are of the architecture "arm64"
    And an installation on that cluster naming the images of a release by its tag
    When the platform is installed
    Then every instance of the platform's own services starts from the release's images
    And nothing in the installation names an architecture

  Scenario: a release is refused when an image of the platform is built for one architecture
    Given a release of the platform whose image "ankka-console" is built for the architecture "amd64" alone
    When the release publishes its images
    Then the release is refused, naming "ankka-console" and the architecture "arm64" it lacks

  Scenario: the platform's cluster suites run on an arm64 runner as they run on an amd64 runner
    Given the cluster suites of the platform
    When the cluster suites run
    Then every cluster suite runs once on a runner of the architecture "amd64" and once on a runner of the architecture "arm64"
    And each run builds the images it deploys on its own runner
    And a cluster suite that fails on one architecture is reported with the architecture it failed on

  Scenario: a service whose image has no build for the cluster's architecture is reported with that reason
    Given a cluster whose nodes are of the architecture "arm64"
    And an image "cart:amd64-only" built for the architecture "amd64" alone
    When a member applies a descriptor for the service "cart" with the image "cart:amd64-only"
    Then the report of "cart" says "Failed"
    And the report names the image "cart:amd64-only" and the architecture "arm64" it lacks
    And the member is shown the same reason by the command line and by the console

  Scenario: a template's build says which architectures the service's image is built for
    Given a developer who starts a Scala service from the template
    When the developer reads the build the template wrote
    Then the build names, in one setting, the architectures the service's image is built for
    And the documentation describes how to build the image of a service for the architecture of its cluster, for each hosting

  Scenario: building the images on a developer's machine builds for that machine alone
    Given a developer's machine of either architecture
    When the developer builds the images of the platform on it
    Then each image is built for the architecture of the machine alone
    And the images are loaded into the developer's local platform as they were before

  Scenario: the documentation says which architectures the platform runs on and what a cluster of one's own machines needs
    Given the documentation
    Then the documentation says that the platform runs on the architectures "amd64" and "arm64"
    And the documentation describes what a cluster of one's own machines must provide: an address for the gateway, a storage class for the databases, and a DNS-01 solver for the wildcard certificate
