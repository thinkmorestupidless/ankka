# Feature Specification: Web Hosting — A User Interface Deployed Beside Its Services

**Feature Branch**: `021-web-hosting`

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description: "Think about how we deploy the UI for an application. We currently deploy
only the backend services, and any UI has to be handled separately, which breaks the ability for a
user to ship everything with the CLI into one place, one cluster. Suggest approaches that follow the
guiding principles the system already has, so a user can package and build a UI (a single-page app,
for instance), and say what they need to integrate with the wider ankka system to get it deployed and
able to talk to the backend services. Decision: a `web` hosting mode — any HTTP process beside a
platform proxy that holds the certificates — rather than a `static` mode first. It is the larger
piece of work and is preferred over an intermediate step that is unlikely to be used."

## Context

An application built on ankka today is deployed in two places. Its services go to the platform with
`ankka services apply`. Its interface goes somewhere else, by some other tool, to some other
address, because the platform runs only images that are ankka services: an image that merely serves
HTTP opens none of the ports the platform expects, never becomes ready, and is reported `Failed`.
The installation's own console is the evidence. It is exactly such a program (a server-rendered
application that calls an API on behalf of the person signed in), and to run in the cluster it had
to implement the platform's transport itself and be installed by hand, outside the control plane.

This feature adds a hosting mode beside `embedded`, `process` and `wasm`: **`web`**. The image is
any program that serves HTTP. The platform runs it beside a **proxy** of its own, in the same
instance, and the two talk over the instance's loopback. The proxy holds every certificate and
terminates every connection from outside, as the sidecar does for a process-hosted service, so the
developer's program needs to know nothing about the platform's transport. Whether that program
renders pages on the server, serves the files of a single-page app, or is a small server that does
both is the developer's choice, and the platform never learns which.

Five things shape this specification.

- **It stays a service.** A web-hosted service has a descriptor, a generation, a history, a status and
  logs. It is applied, exposed, scaled, restarted, paused and deleted with the commands every
  service has, shown in the console as every service is, and counted in its organization's quota.
  There is no new verb in the CLI and no second kind of thing in a project.
- **It is not an ankka application.** A web-hosted service registers no components, so it has no journal,
  no database, no cluster to form and no peers to find. It is as many identical copies of one
  program as the descriptor asks for.
- **It calls other services as itself.** The proxy gives the process an address, inside the
  instance, at which a request for another service is sent on as the web-hosted service: with its
  certificate, to the service named, under the rules a Scala service's own service client follows.
  The service called sees who is calling and its access rule decides. This is what lets an
  application's backends stay private. They admit the web-hosted service by name and are never exposed, and
  the browser talks only to the web-hosted service.
- **A browser can reach a backend on the interface's own address without the interface's code doing anything.**
  The descriptor may *mount* a service of the same project under a path. The proxy passes requests
  under that path to the mounted service, which sees them as the internet's, and passes everything
  else to the process. A single-page app whose image can only serve files therefore calls its backends on
  its own origin, with no cross-origin rules and no backend address built into its bundle.
- **Nothing a request says is trusted.** The process is told who called by the proxy, which read it
  from the caller's certificate, and anything in the request that claims the same is removed first.
  The rule that a request cannot choose who it is holds for a program that has never heard of ankka.

One interface stands in front of as many services as the application has. A descriptor's mounts
are a list, one path for each service the browser reaches; the calling address takes the name of
whichever service the process is calling; and nothing ties a service to one interface, so two
web-hosted services may mount or call the same one. The platform has no notion of an interface
belonging to a service.

Two alternatives were considered and are not taken.

- **A `static` hosting mode**, in which the image hands over files and the platform's own server
  serves them, is smaller and covers single-page apps only. It was set aside as an intermediate
  step: an application whose interface renders on the server, or keeps its users' sessions on the server so
  that no token reaches the browser, would still be deployed elsewhere. A single-page app is served
  under `web` hosting by a process that serves files.
- **Routing mounted paths at the installation's gateway**, straight to the mounted service, would
  make the internet that service's caller. Applying one service's descriptor would then put another
  service on the internet, and exposure is deliberately a command given per service. Under this
  specification a mounted request reaches the service through the web-hosted service's proxy, and the
  service is told it came from the internet. It is served only if the service's own access rule
  admits the internet, which is its author's decision and nobody else's. A service that admits only
  the web-hosted service is reached by the process's own calls and by no mount.

The audience is a team that has services on ankka and an interface for them, and wants one
project, one CLI and one cluster to hold the whole application.

## Clarifications

### Session 2026-10-02

- Q: Who may reach a web-hosted service's process? → A: The proxy enforces it. By default it admits only
  the internet; the descriptor may name the services it also admits (by name, by project and name,
  or every service of its project). The process is still told who called. (The analysis session
  below adds two things: the service always admits itself, and the internet also reaches it under
  a mount by a web-hosted service of its own project.)
- Q: How does a mounted service see a request under a mount? → A: As the internet's, exactly as a
  request through its own hostname. Only the process's own calls arrive as the web-hosted service. A
  mounted service must admit the internet to serve a mount, and still needs no hostname. A service
  author writes and reads nothing new; the platform's runtime is what tells the two apart.
- Q: Are the proposed words right? → A: A service with web hosting is a **web-hosted service**
  (formerly referred to as "web service", which elsewhere means any HTTP API and is now refused).
  `process` and `proxy` are kept: `process` is the developer's program in web hosting as in process
  hosting, and `proxy` stays distinct from `sidecar` because it hosts no components. `web hosting`,
  `mount`, `calling address` and `interface` are settled as written. No term remains proposed.
- Q: Who chooses the port the process listens on? → A: The descriptor may state it, so an image
  that listens on a fixed port can be deployed unmodified. The platform sets `PORT` to that value,
  or to its own default when none is stated. `PORT` still cannot be set in the environment.
- Q: Is it one interface per service, or one interface over many services? → A: One over many. A
  web-hosted service mounts any number of its project's services, each under its own path, and its
  process calls any number by name; a service may be mounted or called by several web-hosted
  services. Stated as FR-044 and as scenarios, since it was only implied before.
- Q: What do the template and the sample's interface demonstrate? → A: Both ways of reaching a
  service, in one template. A single-page app served by a small server of its own: the browser
  reaches one backend route through a mount, and the server makes one call through the calling
  address. The shopping cart's interface has the same shape.

### Session 2026-10-02 (planning)

These were settled while planning; the reasons are in `research.md`.

- **The descriptor's fields are `mounts`, `callers` and `processPort`** (R4, R9). A caller entry is
  `"<service>"`, `"<project>/<service>"` or `"*"`, every service of the project.
- **A call names its service in the first segment of the path** (R10): `<service>/…` in the same
  project and `<service>.<project>/…` in another.
- **A request under a mount is sent under a second certificate** (R3), whose identity the runtime
  reads as the internet when it belongs to a service of its own project. A runtime from before this
  feature already refuses that identity, with no change to it, which is what FR-042 asks.
- **FR-033 is restated as what is measured** (R14). A mount is the proxy's behaviour, not the
  project's, so the project's own tests cannot exercise one. They cover the server's call and the
  app's request against stand-ins; the platform's suite for the template requests both through the
  real local command.
- **Three scenarios moved to `features/web-hosting/isolation.feature`** (R16): what the network
  refuses can only be shown on a cluster, and a feature file is run whole by one suite. For the same
  reason the scenario of a mount with no service being applied and marked is in
  `deploying.feature`: it is the control plane's answer, not the proxy's.
- **`ankka services logs` reads a process-hosted service too** (R11), by the same change that makes
  it read a web-hosted one. The assumption that called this welcome but optional is now a fact.
- **A service may change to or from web hosting** (R7). What it had as a service made of
  components (a cluster certificate, a role, a database) is kept until the service is deleted, and
  admits nothing the web-hosted pod listens on.
- **The proxy's engine cannot upgrade a connection** (R1): a request asking for one is passed on
  as an ordinary request. That is why the assumption says WebSockets are not promised.

### Session 2026-10-02 (analysis)

These came out of the cross-artifact analysis.

- **A descriptor cannot read a secret the platform keeps a certificate in** (FR-045). Without the
  rule a descriptor could hand its own process the key a request under a mount is sent with, or a
  sibling's certificate, through a variable taken from that secret. The rule is for every hosting.
- **A request under a mount is the internet's only within a project** (FR-041). A mounted service
  reads it so only when the web-hosted service that passed it on is in its own project, which is the
  only place a mount can be declared. From any other project it is refused.
- **The internet reaches a web-hosted service two ways** (FR-040): through the gateway, or under a
  mount by a web-hosted service of its own project. So a web-hosted service that is not exposed can
  still be reached from the internet when a sibling mounts it, and its list of admitted services
  does not prevent that. Only its own project's descriptors can make it so.
- **A web-hosted service always admits itself** (FR-040), as an endpoint's rule may admit its own
  service. Its process can call it by name.
- **A web-hosted service mounted under another is told the address the browser used** (FR-018),
  as the mounting service's proxy stated it. That proxy is the platform's, and nothing else holds
  the identity it speaks with.
- **SC-004 is restated as what the sample can show.** The cart's endpoint admits every caller,
  deliberately, so the sample shows that the cart has no address of its own and works through the
  interface. That a service admitting only the web-hosted service refuses everyone else is shown
  by a scenario with such a service.
- **The local command chooses the process's port when it runs the process** (FR-031), so it does
  not depend on a port being free on the developer's machine.
- **FR-037 is shown by comparing what is rendered**, before and after, for a service of each
  existing hosting. A cluster running the platform as it was before this feature cannot be built
  inside a test.
- **What an instance keeps to itself is shown with story 2.** The three scenarios of
  `isolation.feature` are run together on a cluster once there is a calling address to isolate, so
  they are listed under the second story; the first story's rendering tests stand for two of them
  until then.
- **FR-045 covers the project's database secrets too**: every Secret of the project's own database
  cluster, where its authorities' keys are, beside the four kinds of certificate a service is
  issued. A service's database credential Secret (`<service>-db`) is not covered: a descriptor may
  legitimately name a Secret of that shape for a database it supplies, and the credential
  authenticates nothing once its service connects by certificate. The limitations say so.
- **The service's own port is checked too** (FR-043): on a web-hosted service it may not be one the
  proxy uses for something else.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An interface deployed with the CLI, beside its services (Priority: P1)

A developer has an image that serves their interface over HTTP and knows nothing about ankka.
They write a descriptor naming the image and `"hosting": "web"`, apply it to the project that holds
their services, and expose it. The service becomes `Ready`, a browser loads the interface at the platform's
hostname for it over HTTPS, and from then on it is operated as any service is: scaled, restarted,
paused, resumed, rolled to a new image without a refused request, read through its logs, deleted.
The process is told on every request who called and what address the request was sent to, so it can
build links to itself and tell the internet from another service.

**Why this priority**: This is the whole of the complaint: an interface cannot be shipped with the CLI at
all. Until an image that only serves HTTP can be applied, become ready and be reached, nothing else
in this feature can be tested. It is also useful alone: an interface that calls nothing, or whose backends
are exposed, is fully deployed by this story.

**Independent Test**: Apply a web descriptor naming an image that serves a page, to a local
installation. Wait for `Ready`, expose it, load the page in a browser at its hostname. Scale it to
three, roll it to a second image while requesting the page continuously, pause and resume it, read
its logs. Apply an image that listens on nothing and read what the service reports.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/web-hosting/deploying.feature`: a web-hosted service is deployed from an image that only serves requests
- added `features/web-hosting/deploying.feature`: a web-hosted service is given no database
- added `features/web-hosting/deploying.feature`: a web-hosted service is private until it is exposed
- added `features/web-hosting/deploying.feature`: an exposed web-hosted service answers at its hostname
- added `features/web-hosting/deploying.feature`: a web-hosted service is scaled, restarted, paused and resumed like any other service
- added `features/web-hosting/deploying.feature`: a web-hosted service whose image changes refuses no request
- added `features/web-hosting/deploying.feature`: the process is told the port to listen on
- added `features/web-hosting/deploying.feature`: a process that never listens is reported with the reason
- added `features/web-hosting/deploying.feature`: a process that stops listening takes its instance out of the service
- added `features/web-hosting/deploying.feature`: the logs of a web-hosted service are what its process printed
- added `features/web-hosting/deploying.feature`: a member reads what the proxy of a web-hosted service printed
- added `features/web-hosting/deploying.feature`: a web-hosted service counts towards the quota of its organization
- added `features/web-hosting/deploying.feature`: a member is shown that a service is a web-hosted service
- added `features/web-hosting/deploying.feature`: the size a descriptor asks for is the size of the process
- added `features/web-hosting/unchanged.feature`: a platform that gains web hosting changes nothing it makes for a service that is not web-hosted
- added `features/web-hosting/requests.feature`: the process is told who sent a request
- added `features/web-hosting/requests.feature`: a service the descriptor does not admit is refused by the proxy
- added `features/web-hosting/requests.feature`: a request cannot say that another service sent it
- added `features/web-hosting/requests.feature`: the process is told the address a request was sent to
- added `features/web-hosting/requests.feature`: a request cannot say that it was sent to another address
- added `features/web-hosting/requests.feature`: an answer reaches the browser as the process makes it
- added `features/web-hosting/requests.feature`: a request the process does not answer in time is answered by the proxy
- added `features/web-hosting/descriptor.feature`: a descriptor for a web-hosted service is refused for what does not apply to it
- added `features/web-hosting/descriptor.feature`: the services a web-hosted service admits are refused when they are malformed
- added `features/web-hosting/descriptor.feature`: a descriptor cannot take a variable from a secret the platform keeps a certificate in
- added `features/web-hosting/descriptor.feature`: every problem with a descriptor is named at once

---

### User Story 2 - The interface's server calls backend services as itself (Priority: P2)

The developer's process is a server: it renders pages, or it keeps each person's session and calls
the application's services on their behalf. It calls a service by name at an address the platform
gives it inside the instance, with an ordinary HTTP client and no certificate handling. The call
arrives at that service as coming from the web-hosted service, so the service's access rule can admit the
web-hosted service and nothing else. The backends are never exposed, and the only thing on the internet is
the interface.

**Why this priority**: This is what separates deploying an interface from integrating one. Without it a
backend the interface needs must be exposed to the internet and protected by something the browser holds;
with it the application has the shape the installation's own console has, where no token reaches
the browser. It follows the first story because there is nothing to call from until a process runs.

**Independent Test**: Deploy a service whose endpoint admits only the web-hosted service by name, and
leave it unexposed. Deploy a web-hosted service whose process calls that endpoint and shows the answer.
Load the page and see the answer; request the backend's own hostname and in-cluster address from
anywhere else and be refused. Call a service that does not exist and read the answer.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/web-hosting/calling-services.feature`: the process calls a service in its project by name
- added `features/web-hosting/calling-services.feature`: the process calls several services, each by its own name
- added `features/web-hosting/calling-services.feature`: the process calls a service in another project by project and name
- added `features/web-hosting/calling-services.feature`: a service that admits only the web-hosted service is not reachable from the internet
- added `features/web-hosting/calling-services.feature`: a service that admits only the web-hosted service refuses every other service
- added `features/web-hosting/calling-services.feature`: a refusal reaches the process as the service made it
- added `features/web-hosting/calling-services.feature`: a call to a service that does not exist is answered and not sent
- added `features/web-hosting/calling-services.feature`: a call that names no service is answered and not sent
- added `features/web-hosting/isolation.feature`: the process cannot be reached except through the proxy
- added `features/web-hosting/isolation.feature`: only the process can use the calling address
- added `features/web-hosting/isolation.feature`: the process is given no certificate

---

### User Story 3 - A browser reaches the backends on the interface's own address (Priority: P3)

The developer's interface is a single-page app, and its image does nothing but serve files. Its descriptor
mounts the application's services under paths of the interface's own address: `/api/cart` is the cart
service. The browser calls those paths on the origin it loaded the page from. The developer writes
no proxy rules, configures no cross-origin access on any service, and builds no backend address into
the bundle, so the same image works in every project and installation.

**Why this priority**: A single-page app is the commonest interface, and the second story alone would make
its developer write a reverse proxy to use it. Mounts hand that work to the proxy. They come third
because they reach a service the way the process's call does, and differ only in whom the service
is told the request came from.

**Independent Test**: Deploy a service, unexposed, that admits the internet. Deploy a web-hosted service
whose image serves one page and whose descriptor mounts that service under a path. From a browser at
the interface's hostname, request the mounted path and a path outside it. Change the mount's path in the
descriptor, apply it with the same image, and request both paths again.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/web-hosting/mounts.feature`: a request under a mount reaches the mounted service
- added `features/web-hosting/mounts.feature`: one web-hosted service mounts several services, each under its own path
- added `features/web-hosting/mounts.feature`: a web-hosted service mounted under another is told the address the browser used
- added `features/web-hosting/mounts.feature`: a request under a mount of a web-hosted service in another project is refused
- added `features/web-hosting/mounts.feature`: two web-hosted services mount the same service
- added `features/web-hosting/mounts.feature`: a request outside every mount reaches the process
- added `features/web-hosting/mounts.feature`: a service that admits only the web-hosted service refuses a request under a mount
- added `features/web-hosting/mounts.feature`: a mounted service too old to know a mount refuses a request under one
- added `features/web-hosting/deploying.feature`: a mount of a service that does not exist is applied and marked
- added `features/web-hosting/mounts.feature`: a request under a mount with no service to call is answered by the proxy
- added `features/web-hosting/mounts.feature`: mounts change without another image
- added `features/web-hosting/descriptor.feature`: a mount is refused when it is malformed
- added `features/web-hosting/descriptor.feature`: a descriptor for a service that is not a web-hosted service is refused for what only a web-hosted service has

---

### User Story 4 - The same interface, unchanged, on the developer's machine (Priority: P4)

The developer runs their services on their machine as they do today and starts their interface's own
development server. With one command from the CLI they get, locally, what the proxy gives a deployed
process: the address at which the process calls services by name, and the mounts from the same
descriptor answering at the same paths. The interface's code holds no address that differs between their
machine and a cluster.

**Why this priority**: An interface that is written against one set of addresses and deployed against
another is where integration bugs live, and the platform's existing rule is that a test can never
pass against something local development does not have. It is fourth because it reproduces, on a
machine, behaviour the earlier stories define.

**Independent Test**: Start the shopping cart locally. In a web project whose descriptor mounts it,
run the documented command and the project's development server. Load the interface in a browser, add an
item through a mounted path, and have the process call the cart by name. Stop the cart and repeat.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/web-hosting/local.feature`: the process calls a service running on the same machine by name
- added `features/web-hosting/local.feature`: mounts answer at the same paths on a developer's machine
- added `features/web-hosting/local.feature`: a request outside every mount reaches the developer's process
- added `features/web-hosting/local.feature`: a service that is not running is named in the answer
- added `features/web-hosting/local.feature`: on a developer's machine every request came from that machine

---

### User Story 5 - Scaffolded, sampled and taught (Priority: P5)

A developer starts an interface with the CLI's initialiser, which renders a web project: a small
single-page app served by a server of its own, its image build, its descriptor with a mount, the
local setup and a workflow that deploys it. The project reaches a service both ways: the browser
through the mount, and the server through the calling address. The
shopping cart sample gains an interface deployed beside the cart, which is what the local
installation's walkthrough shows and what the platform's own cluster tests deploy. The documentation
takes a developer from an empty directory to a deployed interface, and the reference pages describe the
mode's fields, its contract with the process and its limits.

**Why this priority**: A hosting mode nobody is shown how to use does not exist to them, and the
sample is the proof that a real interface, not a test double, deploys through the path the earlier stories
build. It is last because every page includes samples from code those stories write.

**Independent Test**: In an empty directory, run the initialiser for a web project, run its tests,
run it locally beside the shopping cart, build its image and deploy it to the local installation.
Bring the local installation up and open the shopping cart's interface. Build the documentation.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/web-hosting/template.feature`: a web-hosted service started from the template passes its tests
- added `features/web-hosting/template.feature`: a web-hosted service started from the template runs beside a service on the same machine
- added `features/web-hosting/template.feature`: a web-hosted service started from the template is deployed to a local platform
- added `features/web-hosting/template.feature`: the shopping cart sample has an interface
- added `features/documentation/web-hosting.feature`: the documentation takes a developer from nothing written to a deployed interface
- added `features/documentation/web-hosting.feature`: the documentation of the descriptor describes a web-hosted service
- added `features/documentation/web-hosting.feature`: the documentation says what the process is told and how it calls services
- added `features/documentation/web-hosting.feature`: the documentation says that a mounted service has to admit the internet
- added `features/documentation/web-hosting.feature`: the documentation says what web hosting does not do

---

### Edge Cases

- **The process listens on every network address, as most HTTP servers do by default.** Its port is then
  open on the instance's own address, beside the proxy's. The network admits no connection to it
  from outside the instance, so on a cluster that enforces network policy it cannot be reached. On a
  cluster that does not, it can, and the caller it is told about is then absent: a request with no
  caller did not come through the proxy. This is the existing limitation that protection depends on
  the network enforcing policy, and the documentation says so for web hosting.
  *(scenario: the process cannot be reached except through the proxy)*
- **The process accepts a request and never answers.** The proxy bounds how long it waits for a
  response to begin, answers that the service did not respond in time, and the instance stays in
  service. A response that has begun may continue for as long as the process keeps producing it.
  *(scenario: a request the process does not answer in time is answered by the proxy)*
- **The process exits.** Its container is restarted by the platform as any container is; while it
  is not listening the instance is not ready and receives no request.
  *(scenario: a process that stops listening takes its instance out of the service)*
- **The image is an ankka service, applied with web hosting by mistake.** It does not listen where
  the platform told it to and has no database or cluster to start with, so it never becomes ready
  and the service reports that its process is not listening. The documentation says what `web` is
  for.
- **A mounted service runs on a version of the platform's runtime from before this feature.** It
  does not know the identity a request under a mount arrives with, so it refuses the request; it
  never serves it as the web-hosted service's.
  *(scenario: a mounted service too old to know a mount refuses a request under one)*
- **A mount's service exists but serves no HTTP, or is paused.** The mount has nothing to call. The
  request is answered as unavailable, and the web-hosted service's status marks the mount.
  *(row of: a request under a mount with no service to call is answered by the proxy)*
- **A mounted service is deleted while the web-hosted service runs.** The same: unavailable, and marked.
  *(row of: a request under a mount with no service to call is answered by the proxy)*
- **A mount and the process both answer a path.** The mount wins, because the request never reaches
  the process. Mounts may not contain one another, so which mount answers is never a question.
  *(scenario: a request outside every mount reaches the process; row of: a mount is refused when it
  is malformed)*
- **A browser holds a page from the previous image during and after a rollout.** Instances of the
  old and new image answer side by side for a moment, so a page from one can ask for a file only the
  other has. The platform does not hold a previous image's files. The documentation says how an interface
  avoids the fault (files named by their content, an index that is always re-read, and a process
  that answers a missing file with "not found", never with the index).
- **A process keeps a person's session in its own memory.** With more than one instance the next
  request may reach another. The platform has no affinity between a browser and an instance, and the
  documentation says a session belongs in a cookie or in a service.
- **A web-hosted service calls itself by name.** It is called like any service, and sees itself as
  caller. It always admits itself.
- **A request to the calling address names no service.** It is answered as a bad request, saying
  what the address expects, and nothing is sent.
  *(scenario: a call that names no service is answered and not sent)*
- **Two interfaces in one installation set cookies for the installation's base domain.** Every exposed
  service is a sibling under one domain, so a cookie scoped to that domain is sent to all of them.
  The documentation says to scope a cookie to the interface's own host.
- **A descriptor changes only its mounts or its environment.** It is a new generation and is
  rolled out as any change to a descriptor is. *(scenario: mounts change without another image)*
- **A person asks for an upgraded connection (a WebSocket).** Not promised by this feature; see
  Assumptions.

## Requirements *(mandatory)*

### Functional Requirements

**The descriptor**

- **FR-001**: A descriptor MUST be able to declare `"hosting": "web"`, meaning its image is a
  program that serves HTTP and is not an ankka service.
- **FR-002**: A web descriptor MUST be refused, by the CLI and by the control plane with the same
  rules and messages, when it declares that the service serves no HTTP, declares a protocol version
  or a runtime version, declares a database of its own, or sets a variable the platform sets. Every
  problem MUST be reported at once, each naming its field.
- **FR-003**: A web descriptor MAY declare mounts, each a path and the name of a service in the same
  project. A mount MUST be refused when its path is not an absolute path of whole segments, is the
  whole address, repeats or contains another mount's path, when its service is not a valid service
  name, or when it names the web-hosted service itself. A mount names a service only by its name: never an
  address, a hostname or another project.
- **FR-004**: Mounts and admitted services (FR-039) MUST be refused on a descriptor whose hosting
  is not `web`.
- **FR-039**: A web descriptor MAY name the services it admits: a service of its own project by
  name, a service of another project by project and name, or every service of its own project. An
  entry MUST be refused when it is not a valid name or is given twice.
- **FR-045**: A descriptor of any hosting MUST be refused when a variable is taken from a secret
  the platform keeps a certificate in, naming the variable and the secret. The process of a
  web-hosted service can therefore be given no certificate by its own descriptor.
- **FR-005**: A web descriptor's other fields (image, environment, labels, annotations, port,
  instance type, instance count) MUST mean what they mean for every service. Every environment
  variable it declares, literal or from a secret, goes to the process.

**Running it**

- **FR-006**: The platform MUST run a web-hosted service's image beside a proxy of its own in each
  instance. The descriptor never names the proxy's image or version.
- **FR-007**: The platform MUST tell the process, through its environment, the port to listen on
  and the address at which it calls other services. A descriptor cannot set either in its
  environment.
- **FR-043**: A web descriptor MAY state the port its process listens on. The platform then tells
  the process that port and passes requests to it there; when none is stated the platform uses a
  default of its own. A stated port MUST be refused when it is outside the range of ports, or is a
  port the platform itself uses in the instance, the service's own port among them. The service's
  own port MUST be refused when it is one the proxy uses for something else.
- **FR-008**: A web-hosted service MUST be given no database, no journal, no cluster membership and no
  permission in the cluster's own API. Its instances are independent copies; any instance count of
  one or more is valid.
- **FR-009**: An instance MUST be ready when, and only when, its process accepts connections on the
  port it was told. The platform calls none of the process's routes to decide this.
- **FR-010**: A web-hosted service whose process is not listening when the deployment's deadline passes
  MUST be reported `Failed`, with a reason that says the process did not listen and on which port.
- **FR-011**: The instance type MUST size the process. What the proxy uses is the platform's own
  and is not taken from the process's allotment.
- **FR-012**: A web-hosted service MUST be applied, exposed, unexposed, scaled, restarted, paused, resumed
  and deleted with the existing commands, report the existing status words, keep a history, appear
  in listings and the console, use its project's registry credential, and count as one service and
  its instances in its organization's quota.
- **FR-013**: A rolling replacement of a web-hosted service MUST refuse no request: a new instance receives
  requests only once ready, and an instance being stopped finishes the requests it has and keeps
  serving until the platform has stopped sending it new ones.
- **FR-014**: `ankka services logs` MUST read what a web-hosted service's process printed, from every
  instance or one, with the options it has for every service, and MUST be able to read the proxy's
  output instead when asked.
- **FR-015**: `ankka services get` and the console MUST show that a service's hosting is `web`, that
  it has no database, the services it admits, and its mounts, each marked when there is no service
  serving HTTP behind it.

**Requests in**

- **FR-016**: Every connection from outside the instance MUST be accepted by the proxy, under the
  same transport rules as every workload's HTTP port, and passed to the process over the instance's
  loopback. The process's container MUST hold no certificate or key.
- **FR-040**: The proxy MUST refuse a request from anyone the descriptor does not admit, before the
  process or a mount sees it. A descriptor that names no service admits the internet and the
  web-hosted service itself, and nothing else; naming services adds them and never removes the
  internet. The internet reaches a web-hosted service through the gateway, or under a mount by a
  web-hosted service of the same project (FR-026), whether or not it is exposed. On a developer's
  machine every request is admitted.
- **FR-017**: The proxy MUST tell the process, on every request, who called: the internet, or a
  service by project and name. It MUST first remove anything in the request that makes the same
  statement, so the process can rely on it.
- **FR-018**: The proxy MUST tell the process the scheme, host and port the request was addressed
  to, so the process can state its own public address, and MUST remove anything in a request from
  the internet that claims otherwise. For a request under another web-hosted service's mount, the
  address is the one that service's proxy stated.
- **FR-019**: The proxy MUST pass a request's method, path, query, headers and body to the process
  and the process's status, headers and body back, changing only what FR-017 and FR-018 name. It
  MUST deliver a response as the process produces it, without holding it until it is complete, and
  MUST pass a request's body on as it arrives.
- **FR-020**: The proxy MUST bound how long it waits for the process to begin a response and answer
  a request that exceeds it as a timeout. It MUST answer a request it cannot pass on, because the
  process is not listening, as unavailable. Neither answer discloses anything about the instance.
- **FR-021**: The network MUST admit no connection to the process's own port from outside the
  instance.

**Calls out**

- **FR-022**: The proxy MUST serve, on the instance's loopback only, an address at which a request
  naming a service and a path is sent on to that service as the web-hosted service. A service in the same
  project is named by its name; one in another project by project and name.
- **FR-023**: A call sent on MUST present the web-hosted service's certificate, MUST be accepted only by
  the service asked for, and MUST find that service as a Scala service's service client does. The
  called service therefore sees the web-hosted service as its caller.
- **FR-024**: The proxy MUST send on the method, the path after the service's name, the query, the
  headers and the body the process gave, and return the service's answer unchanged whatever its
  status. It does not retry and does not follow redirects.
- **FR-025**: A call naming a service that cannot be found MUST be answered without anything being
  sent, as unavailable, naming the service. A call naming no service MUST be answered as a bad
  request.

**Mounts**

- **FR-026**: The proxy MUST answer a request whose path is a mount's path, or lies under it, by
  passing it to the mounted service, found and verified as FR-023 describes and carried as FR-024
  describes, with the mount's path removed from the front of the request's path. Every other request
  goes to the process.
- **FR-041**: A mounted service MUST be told that a request under a mount came from the internet,
  exactly as for a request through its own hostname, and never that it came from the web-hosted service.
  This holds for a mounted service of every hosting mode, and its author writes and reads nothing
  that is particular to web hosting. It holds only for a web-hosted service of the mounted
  service's own project: a request that claims to be under a mount of another project's service
  MUST be refused.
- **FR-042**: A mounted service whose runtime predates this feature MUST refuse a request under a
  mount. It MUST NOT serve it as a call from the web-hosted service.
- **FR-027**: A mounted service does not have to be exposed, and mounting it MUST NOT expose it: it
  gains no hostname and no route of its own. Whether it serves a mounted request is its access
  rule's decision, made about the internet as caller, and its refusal is returned as given.
- **FR-028**: Applying a web descriptor MUST succeed whether or not a mounted service exists.
  A request to a mount with no service serving HTTP behind it MUST be answered as unavailable.
- **FR-044**: A web-hosted service MAY mount any number of services of its project, each under its
  own path, and its process MAY call any number of services. A service MAY be mounted or called by
  any number of web-hosted services. No limit on either is the platform's.
- **FR-029**: A change to a descriptor's mounts MUST take effect by applying the descriptor, with
  no change to the image.

**On a developer's machine**

- **FR-030**: The CLI MUST offer one command that gives a process running on the developer's
  machine what the proxy gives a deployed one: the calling address, reaching each service where it
  runs locally, found as a locally run Scala service finds another; and the mounts of the project's
  descriptor, answering at their paths in front of the developer's own server.
- **FR-031**: Locally the process MUST be told the caller is the local machine, a service that is
  not running MUST be named in the answer, and no certificate, cluster or container is needed. When
  the command runs the process itself it MUST choose a free port for it and tell the process that
  port.
- **FR-032**: The addresses and paths an interface uses to reach its services MUST be the same locally and
  deployed. Only values the platform gives through the environment differ.

**Scaffolding, sample and documentation**

- **FR-033**: `ankka init` MUST be able to render a web project: a small single-page app, a process
  that serves it and makes one call to a service through the calling address, its image build, a
  descriptor with a mount that the browser reaches a service through, the local setup of FR-030, a
  workflow that builds and deploys it, and the skills a coding agent uses. The project's own tests
  MUST exercise the server's call and the app's request, against stand-ins; the platform's suite for
  the template MUST request both the mount and the call through the real local command. The
  project's own tests MUST pass as rendered, and the platform's suite for the template MUST fail, not skip, when its tools are
  missing and it was asked for.
- **FR-034**: The shopping cart sample MUST gain an interface of the template's shape, deployed
  as a web-hosted service beside the cart on a local installation, with the cart not exposed: the
  browser reaches the cart through a mount, and the interface's process calls the cart through the
  calling address.
- **FR-035**: The documentation MUST gain a guide from an empty directory to a deployed interface; a page
  on what the process is told and how it calls services; the descriptor reference's new fields with
  every refusal's message; and the limits of web hosting in the limitations page. It MUST say that a
  mounted service has to admit the internet, that a request under a mount is the internet's and a
  call from the process is the web-hosted service's, and that a service which must know which person is
  asking has to check that itself.
- **FR-036**: The local installation's deploy script MUST build and load whatever a web-hosted service
  needs, so that applying a web descriptor to a fresh local platform works with nothing done by hand.

**What must not change**

- **FR-037**: Services of every other hosting mode MUST be rendered exactly as before: adding web
  hosting restarts no instance of any existing service and changes none of its objects.
- **FR-038**: The installation's gateway MUST still do nothing but route. No rule for a
  web-hosted service's authentication, mounts or headers is placed there.

### Key Entities *(include if feature involves data; each a term in the project glossary)*

- **web hosting**: The hosting mode in which a service's image is any program that serves HTTP, run
  beside the platform's proxy.
- **web-hosted service**: A service with web hosting. It registers no components.
- **process**: The developer's program in a web-hosted service's instance (as in a process-hosted
  service's). It serves HTTP on the instance's loopback and holds no certificate.
- **proxy**: The platform's program beside the process. It accepts every connection from outside
  the instance, tells the process who called, and sends on the process's calls to other services.
- **calling address**: The address, inside an instance, at which the process calls another service
  as the web-hosted service.
- **mount**: A path of a web-hosted service, with the service of the same project that answers requests
  under it.
- **interface**: What a person uses a set of services through, in a browser. One is deployed as a
  web-hosted service.
- **path**: The part of a request that says what is asked for, after the service's address.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An application made of an interface and its services is deployed, exposed, updated
  and removed using the CLI alone: no step uses another tool against the cluster, and nothing is
  hosted anywhere else.
- **SC-002**: A developer with the CLI, Docker and Node, starting from an empty directory, has a
  interface deployed to a local installation and loaded in their browser within 15 minutes, following the
  guide.
- **SC-003**: A web-hosted service is `Ready` within 30 seconds of being applied when its image is already
  on the node, since it waits for no database and no cluster.
- **SC-004**: In the shopping cart sample, the cart has no address of its own on the internet: it
  is not exposed and has no hostname, while every cart operation works through the interface.
- **SC-005**: Across the rolling replacement of a web-hosted service, 200 of 200 requests made continuously
  are answered, at one instance and at three.
- **SC-006**: The same interface source and the same descriptor run on the developer's machine and on a
  cluster: no address or path that reaches a service differs between them.
- **SC-007**: A request passed through the proxy to a process on the same instance takes less than
  5 milliseconds longer, at the median, than the same request made to the process directly.
- **SC-008**: Every refusal of a web descriptor names the field at fault, and a descriptor with
  several problems is told all of them in one answer.
- **SC-009**: No request can make a process believe it came from a caller it did not: every attempt
  in the test suite to claim a caller or a public address from outside is seen by the process as
  what the proxy read from the connection.
- **SC-010**: After the feature is installed on an installation with running services, none of them
  has restarted and none of their objects has changed.

## Assumptions

- **A web-hosted service that is not exposed, names no service and is mounted by none is reachable
  by nothing but itself.** That is the default, and it is deliberate: on an installation several organizations share, another
  organization's workload can open a connection to any service's HTTP port, and a program that has
  never heard of ankka will not check who is calling.
- **The process is told its port by the `PORT` variable**, the convention most HTTP servers and
  hosting platforms already follow. An image that listens on a fixed port of its own is deployed by
  stating that port in the descriptor, as `processPort`.
- **Mounts reach services of the same project only.** A process that needs a service in another
  project calls it itself, by project and name.
- **A mount removes its own path** before the request reaches the mounted service, so `/api/cart`
  mounted on the cart turns `/api/cart/carts/c1` into the cart's own `/carts/c1`.
- **The contract between the proxy and the process is small and grows only by addition**: a port, an
  address, and a few facts stated on each request. A web descriptor therefore declares no protocol
  version.
- **Upgraded connections (WebSockets) are not promised.** Ordinary requests and streamed responses
  are. An application that needs to push to a browser uses a stream of events.
- **The installation's own console is not moved onto web hosting** by this feature. It has to exist
  before any project does, and it is installed with the platform.
- **`ankka services logs` reads a process-hosted service too**, by the same change that makes it
  read a web-hosted one: both have two containers, and the command now says which it reads.
- **One template.** The initialiser renders one kind of web project, which shows both the mount
  and the calling address. An application that renders its pages on the server is taught by the
  documentation, not by a second template.
- **Out of scope, each a separate decision**: authenticating an application's own users (a token
  check the platform ships for a service's access rule in every language, any identity realm per
  project); a hostname of the developer's own; cross-origin access rules on endpoints; any policy at
  the gateway; a content delivery network or the platform keeping a previous image's files; traces,
  metrics and topology for a web-hosted service's requests and calls; affinity between a browser and an
  instance; restricting where a web-hosted service may connect to.
