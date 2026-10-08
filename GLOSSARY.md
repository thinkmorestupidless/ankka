# Glossary

The words the platform's features use, each in exactly one sense. A term marked *Proposed.* has
still to be settled by `/speckit-clarify`: those under *Topic sources*, *Modules*, *Cross-project access*, *Backups and recovery* and the retention terms under *Broker*, at present. The platform's established words
are defined as `docs/reference/glossary.md` defines them for the people who build on it. The
shopping cart sample has a glossary of its own, in `samples/shopping-cart/`.

## The platform

### service
A set of components registered together and run as one.

### component
A unit of a service that the platform hosts: an event sourced entity, a key value entity, a view,
a consumer, a workflow, a timed action, an agent or an endpoint.

### kind
What sort of component a component is: event sourced entity, key value entity, view, consumer,
workflow, timed action, agent or endpoint.

### event sourced entity
A component that keeps its state as the events that led to it.

### key value entity
A component that keeps only its latest state.

### entity
An event sourced entity or a key value entity.

### entity id
The id of one entity of a component, such as one cart's. There is no limit to how many there are.

### event
A fact an event sourced entity recorded, such as an item having been added.

### state
What an entity knows now.

### view
A component that keeps a table it can be asked questions of, built from the events or the state
it reads.

### consumer
A component that reacts to the events, the state or the topic it reads, by calling other
components or publishing to a topic.

### workflow
A component that runs a process of several steps and survives a restart part way through.

### step
One unit of a workflow's work, or of a run's. A step may call other components; a run's step
uses one pattern.

### timed action
A component whose handlers are run by timers.

### timer
A call set to be made later.

### agent
A component that carries out a task by talking to a model.

### endpoint
A component that turns requests from outside the service into calls to its components.

### route
One kind of request an endpoint serves, named by its method and its path with the changing parts
left as names: "POST /carts/{cartId}/items".

### handler
A part of a component that the platform runs: a command, a step, a timed action's action or an
agent's handler. Each is declared with a name.

### command
A handler that may change the state of an entity or of a workflow.

### topic
A named stream of messages that a view or a consumer can read and a consumer can publish to.

### instance
One running copy of a service.

### cluster
The machines a deployed service's instances run on.

### platform component
A component the platform registers in a service for its own purposes, such as the
entity that keeps an agent's sessions. It is not one of the components the service's developer
wrote. It is not a component of the platform's own services, such as the control plane's.

Avoid: system component, internal component

### platform
What hosts services: on a developer's machine, and in a cluster for the services deployed to it.

### HTTP endpoint
An endpoint that serves HTTP: it turns each request to one of its routes into calls to
components. Until gRPC endpoints, the only kind of endpoint.

### installation
One platform in one cluster, with every project and service deployed to it.

### workload
Something running in a cluster: an instance of a service of the installation, or a
program that is not of the installation at all.

### descriptor
The document that states how a service is to be deployed. A member applies it.

### embedded
Of a service: written in Scala, so that the developer's program is itself the
platform's. The opposite of hosted as a process or as a module.

### process
Of a service hosted as a process: the developer's program in a language other than Scala, run
beside the platform's own.

### ready
Of an instance: able to answer what its service declares it serves. Calls are sent
only to an instance that is ready. Of a service: every instance it asked for is.

### exposed
Of a deployed service: reachable from outside the cluster, at its hostname. Of the installation's
broker: reachable by registered machines, through the gateway, at hostnames of the base domain.

### hostname
Where an exposed service answers from outside the cluster, for HTTP and for gRPC, or where an
exposed broker answers a registered machine. The platform derives it; nobody chooses it.

### gateway
What every request and call from outside the cluster passes through to reach an exposed service.
It is the calling workload of each of them, whoever sent it; an ACL that admits the
gateway admits anyone who can reach the hostname.

### certificate
What the platform issues a deployed service to prove which service it is, to what it
calls and to what calls it.

### test kit
What a developer's test starts a whole service with, on the developer's machine.

### control plane
The part of the platform that members operate their organizations, projects and
services through.

### module
A service built as a WebAssembly module that the platform's own program loads. It has
no environment of its own and reads its configuration from the platform, which withholds its own
settings.

## People

### developer
A person building a service, who runs it on their own machine.

Avoid: user

### project
A group of services deployed together, which a member may operate.

### member
A person, or a machine acting for one, who belongs to the organization a project is in and may
operate its services.

### local console
What a developer reads the services running on their own machine through.

### console
What a member reads the services deployed in their projects through.

### credential
Something that proves who its holder is and lets them reach what that identity may reach.

### documentation
What the platform publishes for the people who build on it.

### deploy token
A credential a machine holds to act as a member of an organization.

## Topology

### topology
What a service says it is made of and how the parts are connected: its components,
the topics they read and publish to, their declared connections and their observed calls. It is
not where a service's instances run, nor how they find each other.

Avoid: graph, map, diagram

### declared connection
A connection a component declared when it was registered: an event subscription, a
state subscription, a topic subscription or a topic publication. Declared connections are exact
and complete: if a component reads something, the connection is declared.

Avoid: edge, declared edge, link

### event subscription
A declared connection from an event sourced entity to a view or consumer that reads
its events.

### state subscription
A declared connection from a key value entity to a view or consumer that reads its
state.

### topic subscription
A declared connection from a topic to a view or consumer that reads it.

### topic publication
A declared connection from a consumer to a topic it publishes to.

### observed call
What the service saw of one component calling another: who called, what was called,
and how many of the calls were handled and how many went unanswered. Observed calls are the calls
made in the window, never a list of every call the service can make. An observed call is not one
call: however many times a caller called a handler, that is one observed call, counted that many
times.

Avoid: call edge

### call
One component asking another to run a handler, or one service asking another for something.

### caller
The component, and the handler or route in it, that made a call.

### unknown caller
Who a call is attributed to when the service cannot tell which component made it,
because it was made outside any handler. It is never replaced by a guess.

Avoid: orphan

### window
The most recent stretch of time whose calls a topology counts. Calls made before it
are forgotten. It is not the window of a service's traces, which is counted in what was recorded
and not in time.

### handled
Of a call: its handler ran. A handled call ended ok, refused or failed. Handled says nothing of
whether the caller was still waiting for the answer.

### ok
Of a handled call: the handler did what was asked.

### refusal
A deliberate "no": a handler's to a call, or the platform's to a person who may not have what they
asked for. A refusal is the service working, not the service failing.

### refused
Of a handled call: it ended in a refusal. Of a person: given a refusal.

### failed
Of a handled call: something went wrong in the handler.

### unanswered
Of a call: its caller got no answer from a handler. An unanswered call timed out or was
undelivered. A call can be both handled and unanswered, when its handler ran and its caller had
stopped waiting; the two are counted apart and never added together.

### timed out
Of an unanswered call: its caller stopped waiting for a reply.

### undelivered
Of an unanswered call: no handler ever ran, because the handler is undeclared or no
instance could be reached. The caller may have been told so; what it did not get is a handler's
answer.

Avoid: unreachable

### undeclared
Of a handler: named by a call, and not one the component declares.

### stream
An answer that arrives a part at a time. A call answered as a stream is one call.

### other services
The services a service calls beyond the limit of those its topology shows by name,
counted together.

### partial
Of a deployed service's topology: missing what at least one of its instances would
have said.

### unsupported
Of an instance: too old to report its topology.

### deployed
Of a service: running in a project on the platform, not on a developer's machine.

## gRPC

### gRPC
A way of asking a service for something by calling a method of a service definition.
A service serves gRPC when it has a gRPC endpoint that can be called; a descriptor declares gRPC
when it says the service serves it.

### HTTP
The way of asking a service for something by a route. A service serves HTTP when it
has an HTTP endpoint; a descriptor declares no HTTP when it says the service serves none.

### gRPC endpoint
An endpoint that serves gRPC: it implements one service definition and turns each call
to one of its methods into calls to components.

### service definition
A named set of methods, written once and shared by a gRPC endpoint, which implements
it, and by whatever calls that endpoint. It is not a service: one service may have gRPC endpoints
for several service definitions.

Avoid: proto service, API

### method
One thing a service definition lets a call ask for. Its request is one message or a
stream, and so is its answer. A method that takes a stream is one whose request is a stream. A
route's method ("POST") is part of the route's name and is always written inside it. It is not an
operation, which is something a member does in the console.

Avoid: rpc

### status
How a gRPC call ended, as whoever called is told: "ok", or the name of one kind of
refusal or failure. Every gRPC call ends with exactly one. It is not a service's lifecycle nor a task status.

Avoid: status code, error code

### message
The words a status carries to say why the call ended as it did.

### metadata
The names and values sent with a gRPC call beside its request.

Avoid: headers

### ACL
An endpoint's access control list: what the endpoint, or one of its routes or methods, states
about who may call it. It denies all, allows all, admits only the workloads it names, admits
granted callers, or asks an authenticator.

Avoid: access rule

### authenticator
An ACL that reads a call and answers one of four things: allow, establishing a
principal; unauthenticated; forbidden; or unavailable, when it cannot tell.

### principal
Who a call came from, as an authenticator established it.

### calling workload
The workload a call to an endpoint came from, as the platform established it: a
service of a project, the gateway, a registered machine, or the local caller. It is read from the
certificate, or from a machine token the platform verified, and never from what the call says.

### local caller
The calling workload of every call on a developer's machine, where there is no
certificate to read.

### gRPC address
Where a deployed service that declares gRPC is reached by the other services of the
installation. It is inside the cluster; it is not the hostname.

### gRPC port
The port a service serves gRPC on. A descriptor may choose it.

### HTTP port
The port a service serves HTTP on. A descriptor may choose it.

### gRPC client
What a service calls another service's gRPC endpoint through, as itself.

### reflection
A service's answer to a tool that asks which service definitions it serves, what methods they
have, and what their requests and answers look like. A service answers it only when its developer
opts in, and then under an ACL stated for reflection alone. It describes every gRPC endpoint of
the service, whatever that endpoint's own ACL.

## Console

### page
What the console shows a member for one thing they read: an organization, a project,
a service, or a section of a service.

### shell
What every page of the console is shown inside: the rail, the bar, the panel and the
inspector around the page, over the backdrop. A host mounts the backdrop and the bar at least,
and the other parts where it has content for them.

### backdrop
The mesh behind every surface of the console, with the glow at its centre and the dot grid
across it.

### rail
The shell's column of areas, marking the one the member is reading and offering
signing out.

### area
One of the things the rail offers: organizations, projects, services, members or
deploy tokens.

### bar
The shell's top edge: where the member is, the page's primary operation and the
member's name.

### panel
The shell's listing of what sits beside the page: a project's services, or an
organization's projects.

### inspector
The shell's column of everything the page offers to be done, with the destructive
operation behind a disclosure at its foot.

### operation
Something a member does to what a page shows: pausing, restarting, exposing,
unexposing, deleting, renaming, inviting, applying a descriptor. It is not a timed action's
action, which is a handler.

### primary operation
The one operation a page puts first, offered in the bar.

### destructive operation
An operation that deletes what the page shows.

### further operations
The operations of a page that are not its primary one, offered together.

### disclosure
A part of a page that is closed until a member opens it, and opens without scripts.

### overlay
Something shown over a page: a menu, a dialog, a tooltip. In the console an overlay
is only an enhancement of a page that exists without it.

### scripts
The code a browser runs for a page. A member's browser may run none, and every
operation completes without them.

### section
One of the things a service's page shows one at a time, each a page of its own: its
overview, its topology, its logs or its history.

### theme
The console's dark or light rendering. The console is dark; a host may choose light.

### surface
A part of the console drawn over the backdrop with the backdrop showing through: the
rail, the bar, a panel, a card or a part of a shape.

### glow
The brightest part of the console's backdrop, at the centre of the screen.

### contrast
How far apart in brightness text and what it sits on are, as a ratio.

### ink
The colour the console's text is set in.

### face
The typeface the console's text is set in, which the console serves itself.

### custom property
One named value of the console's look — a colour, a face, a radius, a space — that a
host may set. It is not a variable, which is set on a service's process.

### package
The console as a host takes it: its pages, its sign-in and its shell, with its
custom properties and its rules.

### website
The hosted product's own pages, which are a host of the console.

Avoid: site

### rule
How the console draws something from its custom properties. A host sets custom
properties and changes no rule.

### origin
Where a page is served from, as its browser names it.

### shape
What the platform runs for a deployed service — its address, the service, its
instances and its database — and how they are joined. It is not the service's topology, which is
what the service is made of.

### live indicator
What a page shows while it is changing as the platform reports.

### accessibility violation
What an automated audit of a page reports against WCAG 2.1 AA.

### reduced motion
A browser setting asking that nothing on a page move of its own accord.

### forced colours
A browser setting that replaces every colour of a page with the browser's own.

### narrow screen
A screen as narrow as a phone's, 400 pixels across.

## Observability

### trace
Everything the components of a service did for one request or one event, and how they were nested.

### request
Something asked of a service from outside it, which an endpoint serves by one of its routes.

### root
Of a trace: what the rest of the trace was done for, and is nested under.

### span
One handler's part of a trace: which component and handler ran, when, for how long,
how it ended, and which span it was nested under. An endpoint's serving of a request is a span,
and so is a call one service makes to another. A trace is made of spans.

### parent
Of a span: the span it is nested under. A root has none, and neither does a span with
an unknown caller, which is never given one.

### trace id
What every span of one trace carries and no span of another does. It is the size and
shape a collector expects, so that spans from different services join by it.

### trace context
What a request, a call or a message published to a topic carries to say which trace it
belongs to and which span made it: a trace id and the span that is to be the parent. A service takes it as it is given; it proves
nothing about who sent it, and nothing is admitted or refused by it.

Avoid: traceparent, trace header

### telemetry
What a service exports about itself: its spans and its metrics. A service's logs are
not telemetry: they are read where they were printed and never exported.

### export
Of an instance: send its telemetry to the collector. What is exported is a copy; the
instance's trace window and its logs are read as before.

Avoid: ship

### collector
The program an installation's services export to. It is the installation's own, not
the platform's; where telemetry is kept and shown after it is the installation's business.

### platform's collector
A collector the platform offers, which an installation may add so that it has
somewhere to send. It keeps nothing.

### telemetry store
What a local platform keeps its services' telemetry and logs in and shows them through: the trace
of a request, the metrics of a service, and what its instances printed, joined to the traces they
belong to. It is for a developer's machine: it keeps nothing when it restarts. An installation
that is not a local platform has none of the platform's; where its telemetry is kept is its own
business. It is not the platform's collector, which keeps nothing at all.

Avoid: observability stack, dashboard

### telemetry settings
The installation's statement of which collector its services export to, and what to
send with the telemetry so that the collector accepts it. They are platform settings the platform
alone sets: a descriptor may not give them.

### metric
A number a service exports about itself: how many times a handler ran and for how
long, counted since the instance started and never over the trace window.

### trace window
The most recent spans an instance holds, which the local console and the console read
traces from. It is counted in spans, not in time: when it is full, each new span replaces the
oldest. It is not the window of a topology.

Avoid: ring, buffer

### lost span
A span that left the trace window before it was exported. It is never exported; a
metric counts how many there were.

Avoid: dropped span, overwritten span

## Deploying

### local platform
A platform on a developer's own machine.

### apply
Of a descriptor: give it to the platform, which makes the service what the descriptor says.

### image
What a service is run from.

### hosting
How the platform runs a service's image: what the image has to be, and what the platform runs
beside it.

### environment
The named values a descriptor gives a service's image when it runs.

### variable
One named value of an environment.

### size
How much of a machine each instance of a service is given, as a descriptor asks for it.

### expose
Make a deployed service reachable from the internet at its hostname. A service is private until
it is exposed. Of the installation's broker: make it reachable by registered machines; an
installation's broker is closed to them until the installation exposes it.

### internet
Everyone and everything outside the platform. A request from the internet reaches a service only
through its hostname.

### admit
Of an ACL, or of a web-hosted service's descriptor: allow a call or a request from the one who
made it.

### secret
A named value the platform keeps for a project, which a descriptor's variable can be taken from.

### database
Where the platform keeps what a service's entities, views and workflows know, and, on the Postgres
backend, the service's secret store. Each deployed service has its own, provisioned by the platform
or supplied by the service.

### logs
What a service's instances printed.

### organization
The group of members that a project belongs to.

### quota
How many projects, services and instances an organization may have.

### pause
Stop every instance of a deployed service and keep the service.

### resume
Start a paused service's instances again. Of a suspended autonomous agent: let it go on with its
task.

### scale
Change how many instances a deployed service has.

### roll back
Of a deployed service: apply again the descriptor of an earlier generation, as a new
generation. The generation goes on counting and nothing recorded is undone; it changes only what
a descriptor states, and brings back nothing the service's database held.

Avoid: revert, rewind, undo

### digest
Of a descriptor, or of a service secret's value: a short value that two share exactly when they
are the same.

Avoid: hash, checksum

### sample
A service the platform publishes as an example, such as the shopping cart.

### platform setting
A variable the platform's own program reads. Some the platform alone sets, and a
descriptor may not give them; the others a descriptor may give, and they are for the platform's
program and never for the developer's: a process is not given them, and a module that asks for one
is told that it is not set.

Avoid: reserved variable, platform variable

## Web hosting

### web hosting
The hosting in which a service's image is any program that serves requests, run beside the
platform's proxy. It is not the hosting of a service made of components, and it is not only for
an interface: any program that serves requests can be run this way.

### web-hosted service
A service with web hosting. It has no components, no database and no cluster of its own; its
instances are copies of one process. It is not any service that answers requests from the
internet: a service made of components with an endpoint is not one.

Avoid: web service, frontend, site, static site

### proxy
In a web-hosted service's instance, the platform's program beside the process. It accepts every
request from outside the instance, refuses one from anyone the descriptor does not admit, says
who sent it, and sends on the process's calls to other services. It hosts no components, which is
why it is not called what the platform's program beside a service in another language is called.

Avoid: sidecar

### calling address
The address, inside an instance of a web-hosted service, at which the process calls another
service as the web-hosted service. Nothing outside the instance can use it.

### mount
A path of a web-hosted service together with the service of the same project that answers
requests under it. As a verb: declare one. The proxy passes a request under a mount to the
mounted service, which is told that it came from the internet. A mount does not expose the
mounted service and gives it no hostname.

Avoid: rewrite, proxy rule

### port
The number a program listens for requests on. A service has one; in a web-hosted service's
instance the process has another, which only the proxy reaches.

### path
The part of a request that says what is asked for, after the address of the service: "/carts/c1".

### interface
What a person uses a set of services through, in a browser. It is deployed as a web-hosted
service.

Avoid: UI, frontend

### template
What the platform gives a developer to start a service from.

### protocol version
What a descriptor says of a service whose image is not made of the platform's own code: which
version of the platform's way of talking to it the image was made for.

### runtime version
What a descriptor says of a service whose image is made with the platform's own code: which
version of the platform it was made with.

### browser
What a person on the internet sends requests with and is shown the answers in: a member's shows
the console, a visitor's a web-hosted service. It may run no scripts, prefer a theme, ask for
reduced motion or force its own colours.

## Identity

### issuer
An identity provider that signs tokens for people and publishes the keys to check them
with. A service lists the issuers whose tokens it accepts, each with the audience it expects. The
installation's issuer is the one the control plane lists.

Avoid: IdP, realm

### token
A signed statement from an issuer of who a person is, sent with a request to prove it.

Avoid: JWT, bearer token

### keys
What an issuer publishes so that the tokens it signed can be checked without asking it
about each one. A service fetches an issuer's keys and keeps them.

### audience
Who a token says it is for. A service accepts a token only for the audience it listed
with the token's issuer.

### subject
The issuer's own id for the person a token names.

### role
A name an issuer gives a person's standing, carried in the token. A handler decides what
a role allows.

### claim
One named value a token carries. The subject, the roles and the audience are claims; a
service reads its own claims by name.

### verified
Of a token: its signature checks against the keys of the issuer it names, that issuer is
one the service lists, it is for that issuer's audience, it has a subject, and it is within its
dates.

### authenticated route
A route whose ACL admits only a request with a verified token.

### challenged
Of a request: answered that a verified token is needed and none was accepted, so the
handler was not run.

### unavailable
Of a request: answered that the service cannot verify tokens at present and the request
should be sent again later.

### tolerance
How long a service goes on verifying with the keys it already holds when an issuer's
keys cannot be fetched.

### fetch timeout
How long a service waits for an issuer to answer for its keys.

### shared secret
A key that both signs and checks a token, so that anyone who can check it can also forge
it. A service never accepts a token signed with one.

## Secrets

### secret store
Where a service keeps its service secrets, apart from everything its components know: in its
database on the Postgres backend, in Secret Manager on the Secret Manager backend. It is not a
component, and nothing a component records or a view is built from ever holds what is in it. An
entity and a view are given none; every other component of the service keeps and reads through
the same one.

Avoid: vault

### service secret
A named value a service keeps in its secret store while it runs and reads back by that
name, such as a credential a person gave it. The value is text, never empty, and no larger than
the secret store's limit. It belongs to the one service that kept it; another
service cannot read it. It is not a project secret, which a member sets before a service starts.

Avoid: runtime secret

### secret key
What a service's secret store encrypts its service secrets with on the Postgres backend. Each
service has its own. The platform makes one for a deployed service unless its descriptor gives
one, and keeps it when the service is deleted; on a developer's machine the developer gives one.
It is a platform setting. It is not an entry of a project secret, and it is not an issuer's keys.

Avoid: encryption key, master key

### encrypted
Of a service secret as the database holds it, or of a personal field as any store holds it:
unreadable by anyone who does not have the key it was encrypted with — the service's secret key,
or the data subject's subject key.

### project secret
A named set of entries the platform keeps for a project, which a descriptor's variable
can be taken from. A member sets and removes its entries; the platform gives its values to a
service's instances when they start and shows them to nobody. A project secret with no entry left
is no longer listed.

Avoid: static secret

### entry
One named value of a project secret. A descriptor's variable is taken from one entry.

### secret backend
*Proposed.* Where an installation keeps service secrets and project secrets: the Postgres backend
or the Secret Manager backend. It is the installation's to say, once, as a platform setting the
platform alone sets; a descriptor may not give it, and a service's code, descriptor and components
are the same on either.

Avoid: secret provider, vault backend

### Postgres backend
*Proposed.* The secret backend an installation is on unless it says otherwise: each service's
secret store in its own database, encrypted with its secret key, and a project secret's entries in
the project's secret in the cluster.

### Secret Manager
*Proposed.* Google Cloud's keeper of secrets, outside the cluster. It holds each service secret
and each entry under a name the platform derives from the project, the service and the secret's
own name, so that two never share one, as versions of which the newest is read; it refuses whoever
has no grant on it; and the access log records every access to it.

### Secret Manager backend
*Proposed.* The secret backend on which service secrets and project secrets are kept in Secret
Manager, each service reaching it as its own identity and nothing in the cluster holding a
credential for Google Cloud.

### Google Cloud
*Proposed.* The cloud Secret Manager is part of, which decides by the grants what each identity
may do there, and keeps the access log.

### grant
*Proposed.* What an identity may do to which secrets in Secret Manager, as Google Cloud enforces
it: a service may keep, read and remove its own service secrets and read its project's entries,
and list nothing; the control plane may add and disable versions of entries and read none; the
cloud provider may read entries and no service secret. The operator asks for a grant and the cloud
provider writes it; nothing else can. A grant follows a service's name, so a service deleted and
deployed again has the one it had.

Avoid: IAM binding, role binding

### cloud provider
*Proposed.* The part of the platform that acts on the cloud for the installation, holding the
power the operator does not: it writes grants, and keeps each project's secret in the cluster in
step with the project's entries in Secret Manager. It is not a judgment provider.

### access log
*Proposed.* Google Cloud's own record of every access to a secret in Secret Manager, naming the
identity that made it and the secret. An installation turns it on, and the platform reports
whether it is on. It is not the read record, which the platform keeps on either secret backend,
and it is not a history.

### read record
*Proposed.* What the platform records of every read, keep and removal of a service secret, on
either secret backend: the secret's name, the project, the service, its hosting, the outcome, the
time, the trace id and the request where known, and the component and its kind where the caller
can be known, which it cannot be through a process or a module; never the value. The control plane
keeps it, never the service's database, so a restore of the database does not rewind it; an owner
may list it; it is kept for the installation's retention.

Avoid: audit trail

### retention
*Proposed.* How long an installation keeps a read record before removing it: a year unless the
installation says otherwise.

### kept count
*Proposed.* How many versions of a service secret Secret Manager keeps for an installation: 2
unless it says otherwise. After a keep, the versions beyond it are removed, oldest first, once the
new one can be read; a version is never disabled, so the newest can always be read.

### synced
*Proposed.* Of an entry on the Secret Manager backend: copied by the cloud provider from Secret
Manager into the project's secret in the cluster, within a minute of being set or removed, so that
a starting instance is given it as before. A service of the project is not started until each
entry it takes a variable from is synced.

### copy check
*Proposed.* What a move offers for a service or a project: for each name, whether its database and
Secret Manager hold the same value, by digest, as equal, different or missing; never a value.

### removal step
*Proposed.* The last step of a service's move: removing its service secrets from its database,
which the platform does only when the service's last copy check reported every name equal. From
then on the service's secret key is given to it and not read.

### Secret Manager fake
*Proposed.* What the test kit gives a test that asks for the Secret Manager backend: a Secret
Manager of its own, with no network and no Google Cloud, that refuses what Google Cloud's grants
would refuse.

Avoid: emulator, mock

## Topic sources

### ankka
*Proposed.* The platform these features describe: what a service is built on, and what runs it.

### deployed service
*Proposed.* A service running on the platform, which knows its project and its name because the
platform tells it.

### local service
*Proposed.* A service run on a developer's machine, outside the platform. It has no project, and a
name only when it states one.

### id
*Proposed.* The name a project, a service, a view or a consumer is declared with.

### row
One entry a view holds, kept under its row key and written by the view from a message,
an event or a state it read.

### topic source
*Proposed.* A view's or a consumer's declaration that it reads a topic, with the start position
and the version it reads at.

### group
*Proposed.* The name under which a topic source reads a topic. The broker delivers each message to
one member of a group, and remembers for each group how far it has read.

Avoid: consumer group

### subscribes
*Proposed.* Of a view or consumer: begins reading its topic under its group.

### start position
*Proposed.* Where a topic source begins reading the first time its group reads the topic:
"earliest", "latest", or a time. It does not apply once the group has read anything.

Avoid: offset reset, initial offset

### earliest
*Proposed.* Of a message: the oldest the broker still retains. As a start position: begin there.

### latest
*Proposed.* Of a message: the newest on the topic. As a start position: begin after it, reading
only what is published from then on.

### retained
*Proposed.* Of a message: still held by the broker. A broker retains messages for a bounded span
and then drops them, so a topic is not a complete record.

### recorded version
*Proposed.* The version a view's rows were last built at, as the service has stored it.

### rebuild
Emptying a view and reading every one of its sources again, when the view is declared
at a higher version than its recorded version: a topic from its start position under a new group,
an entity from the first thing it recorded. A rebuild of a view that reads a topic reaches back
only as far as the broker retains; an entity keeps everything it recorded.

Avoid: replay, rewind

### emptied
*Proposed.* Of a view: every row removed at once, at the start of a rebuild.

Avoid: truncated, cleared

### behind
*Proposed.* Of a view on one instance: declared at a lower version than its recorded version. A
view that is behind reads nothing and writes nothing, and is never rebuilt downward.

### registers
*Proposed.* A service hands a component to ankka at startup. A component ankka refuses is refused
there, before the service takes any request.

### registration
*Proposed.* The act of a service registering a component.

### rolling update
*Proposed.* Replacing a service's instances one at a time, so that old and new instances run
beside each other until the last old one stops.

### rolled back
*Proposed.* Of a service: deployed again as an earlier build, after a later one.

### log
*Proposed.* The lines a running service writes about what it is doing, read by whoever operates
it.

### metrics
*Proposed.* The numbers a running service publishes about itself for a monitoring system to read.

### release
*Proposed.* One published version of ankka. A service is built on one release and may run on a
platform at a later one.

### limitations
*Proposed.* The part of the documentation that lists what ankka does not do.

## Reconciliation and service clusters

### operator
The part of the platform inside the cluster that makes each deployed service what the
control plane recorded for it, and reports what it sees of the service's instances. It is not a
person: a person who operates a service is a member.

Avoid: controller, reconciler

### generation
How many times a deployed service's descriptor has been applied, the service restarted and the
service rolled back, counted by the control plane from 1. A service is at one generation, the
latest. An apply and a roll back each record the descriptor of their generation; a restart records
none. A report names the generation it describes.

Avoid: revision

### report
What the platform reports of a deployed service, as the operator last saw it: its lifecycle, how
many of its instances are ready of how many it asks for, the generation it describes, and why.

Avoid: observation

### unconfirmed
Of a deployed service's lifecycle: the last one recorded, which the control plane cannot confirm
because it cannot reach the cluster.

Avoid: stale

### service cluster
The instances of one service joined together, so that each entity is active on exactly
one of them and a request that arrives at any of them reaches it. It is not the cluster the
instances run on.

Avoid: node cluster

### build
What a developer makes from a service's code: the same build runs on a developer's
machine and deployed.

## Databases, exposure and zero trust

### provisioned
Of a database: made by the platform for one service, the first time the service is
deployed with a descriptor that says nothing of a database, and kept for it from then on. It is not
a database the descriptor declares of its own.

### authority
What issues certificates, and what a certificate is checked against. An installation
has one for the connections within a service cluster, one for the connections between services,
and one for each project's databases.

Avoid: CA, certificate authority

### mutually authenticated
Of a connection: encrypted, with each end having shown the other a certificate the installation's
authority issued, naming who it is.

Avoid: mutual TLS, mTLS, proven

### in the clear
Of a connection or a request: readable by anything on the network between its two ends.

Avoid: plain, plaintext

### base domain
The installation's own domain, under which every hostname is made. It is set once for
the installation, and never for a project or a service.

### unexpose
Make an exposed service private again. Only its hostname stops answering; the service
and its instances are left as they were.

### renew
Of a certificate: replace it with a new one before it expires. The platform renews every
certificate it issued, every few hours, and nothing restarts for it.

Avoid: rotate

### command line
The platform's program for the command line, through which a person or a machine operates
the control plane and a developer starts services from a template.

## Applications, deploying from GitHub and logs

### library
A published part of the platform that a service is built with, such as the test kit. The parts of the platform that run services, such as the control plane, are programs and are not libraries.

### owner
A member of an organization who may also manage it: invite people into it, remove its
members and change their roles, rename it, manage its deploy tokens and delete it. An organization
always has at least one owner, and a deploy token is never one.

### revoke
Of a deploy token: end it, so that no request with it is admitted again. Of an accepted grant:
end it, by the grantor and without the grantee, so that it opens nothing again.

### job
One run of a repository's automation on GitHub, on one machine, whose commands run one after another. It is not a workflow, which is a component.

### GitHub action
What a job uses to install the command line at a version it names, point it at a control plane and authenticate it with a deploy token for every later command in the job.

### repository
Where a service's code is kept on GitHub, with the deploy settings its jobs read.

### deploy settings
What a repository holds for the job that deploys its service: the control plane's address, a deploy token and a project, and where needed an authority.

### registry
Where images are kept for a cluster to pull. The platform runs none.

### registry credential
What a cluster pulls a project's images from a private registry with: the registry, a username and a password, registered once for the project. The password is given to the cluster and never shown back.

### pull
Of a cluster: fetch a service's image from its registry before an instance can start.

### unattributed
Of time in a trace: spent inside a part of the trace that none of the parts nested under it account for, such as waiting on a model or on the database.

### history
Who did what to a service, a project or an organization, and when, as the control plane recorded
it: what was done, at which generation, by whom and when, newest first. A project's history holds
every change to its grants; an organization's, every change to a grant its registered machines
were offered. It keeps the most recent and forgets the rest. It is
not what a service's instances printed, and not what its entities recorded.

Avoid: audit log

## Organizations

### invitation
An email address an owner has asked to make a member of an organization, with the role
it will have. It is pending until a person whose verified email is that address claims it, and an
owner may revoke it.

Avoid: invite (as a noun)

### pending
Of an invitation: neither claimed nor revoked. Of a grant: offered to another organization and
neither accepted, declined nor withdrawn; it opens nothing.

### claimed
Of an invitation: made into a membership by a request from a person whose verified email
is its address.

### platform administrator
A person, or a machine, whom the installation's issuer names as able to act on every
organization of the installation, whether or not they are one of its members.

Avoid: superuser

### disabled
Of an organization: stopped by a platform administrator. Its running services are
suspended and its members may read it but change nothing in it, until it is enabled.

### enabled
Of an organization: not disabled. Enabling a disabled organization brings back the
services it suspended.

### suspended
Stopped, keeping everything it has, until something other than its members brings it
back: a service whose organization is disabled, or an agent instance told to suspend, which calls no
model until it is resumed. It is not paused: a paused service runs again only when its members resume it.

### actor
Who did a thing the control plane recorded: a member, a machine, or a platform
administrator acting as one. A thing recorded before actors were recorded has none.

### usage
How many projects, services and instances an organization has, counted against its quotas: the
instances are every service's minimum instances added up. Pausing, suspending or disabling changes
none of it. It is not model usage nor judgment usage.

### model usage
What a model reports it used for a turn or an iteration, in the units it charges by. It is never
added to judgment usage.

### judgment usage
What a judgment provider reports it used for a judgment, in the units it charges by. It is never
added to model usage.

### minimum instances
How many instances a descriptor asks the platform to run of a service, and what a quota
counts.

## Signing in

### signs in
Of a member: proves who they are to the installation's issuer, once, so that their
machine can obtain tokens for them. It is done in a browser, which need not be on the same machine.

### sign-in
What is kept after a member signs in, on their machine or by the console for their browser, from
which a new token is obtained when one has expired, until the issuer ends it.

### code
What a member is shown when they sign in, and confirms in a browser, to tie the browser
to the machine that asked.

### protocol library
The library each release publishes of the control plane's requests, answers and rules
for a descriptor, for programs that drive the control plane from outside the platform.

## Languages

### SDK
The library a developer writes a service with, one for each language the platform hosts.
A service built with one says which SDK and which version of it.

Avoid: guest library, PDK

### protocol
The platform's way of talking to a process or a module: the messages that pass between
the platform's own program and the developer's code. A service's code speaks one protocol version.

### ABI version
Which version of the platform's way of loading and calling a module a module was made
for. The platform loads only a module made for an ABI version it speaks.

### conformance suite
What defines a compatible SDK: it drives a reference service through every conversation
of the protocol and names each behaviour it checks as passing or failing.

### reference service
A service each SDK has that declares the same components, handlers and routes in its
language, for the conformance suite to run against.

### encoding
The one way the platform writes every value a service records or sends, the same in
every language, so that what one language recorded another reads.

### fixture
A value published with the protocol together with exactly what the encoding writes for
it, which every SDK must read and write again byte for byte.

### snapshot
An entity's state as the developer's code wrote it at one event, kept so that recovering
the entity needs only the events after it.

### recovered
Of an entity: given back its state, from its snapshot and the events after it, before its
next command runs.

### query
Something that only reads: a handler that reads an entity's state and may not change it, or a
view's declared query, which reads the view's table.

### component test kit
What a developer's test runs one component with, with no platform, no database and no
network, showing each effect as values. It is not the test kit, which starts a whole service.

### stateless
Of a component in a module: handed its state with every call, and handing back the new
one.

### stateful
Of a component in a module: handed its state once, when its entity is loaded, and keeping
it between calls until the entity is passivated.

### loaded
Of an entity: held by one instance, ready to handle commands.

### passivated
Of an entity: put out of memory after a time unused, to be loaded again by its next
command. Nothing of it is lost.

### package registry
The public place a language's libraries are published to and installed from, where each
SDK is published at the platform's version.

### skill
A set of the documentation's pages packaged for a coding agent to load: the rules for
building a kind of thing on the platform, and how each language differs.

### coding agent
A program that writes code for a developer, reading the platform's skills to do so. It is
not an agent.

### Akka
The platform whose component model ankka reimplements, and whose behaviour ankka's documentation says where it differs from.

## Agents

### request agent
An agent that answers one message at a time, in a session, while its caller waits. It is not an autonomous agent.

### model
What an agent talks to: it is sent instructions, a conversation and tools, and answers
with text or asks for tools to be run. It is not a judgment provider.

### session
One conversation of an agent, with every turn of it, which the platform keeps under
an id as it was held, never the developer's code. An autonomous agent's work on one task is a
session of its own.

### turn
One message sent to an agent's session and the reply to it.

### conversation
What an agent and its model have said to each other in one session, which the platform keeps as the session's memory. A judgment neither reads nor writes one.

### reply
What an agent's model answers to a request, as the agent gives it back to its caller.

### input
Of an agent: what goes into its model, a request's message or a task's instructions. A guardrail on the input checks it before the model is called.

### output
Of an agent: what comes out of its model, a reply or a task's result. A guardrail on the output checks it before it is remembered.

### guardrail
A check on what goes into an agent's model or comes out of it, declared by the
developer, which refuses an interaction it does not allow. A refusal by a guardrail is a refusal.

### scripted model
A model a test gives its agent, which answers from a script in order. A script that runs out fails the test.

### script
The answers a scripted model or a scripted judgment provider gives, in order.

### tool
Something an agent offers the model to call: a function of the agent's own, declared
with a name, or one an MCP server has. Where a gRPC feature says "a tool" of something that reads
reflection, it is the everyday word for a developer's program, and not this.

### tool call
The model asking for one tool to be run, with arguments. It is not a call, which is
one component or service asking another: a tool call is made by the model and run by the agent.

### arguments
The values a tool call carries for its tool.

### approval
A person's "yes" to a tool call before it runs. A tool, or an MCP server, that
requires approval is one whose tool calls wait for it.

### approval request
What an agent records, and gives its caller instead of an answer, when the model makes
a tool call that requires approval: it has an id, names the tool and carries the arguments. It is
awaiting a decision until a person decides it or it is discarded.

Avoid: pending call

### awaiting
Of an approval request: not yet decided and not discarded.

### decision
A person's answer to an approval request: approved or refused, with the name of who
decided and an optional note. An approval request is decided once. When its time limit passes
with no decision, the platform decides it as refused. A refused approval request is a refusal the person made, and
the tool never runs.

### approved
Of an approval request, or of its tool call: a person decided that the tool may run.

### note
The words a person gives with a decision, which the model is told.

### discarded
Of an approval request: no longer awaiting a decision, with none made, because what it
was asked for is over.

### subscriber
One who is told what an autonomous agent does as it does it.

### compaction
Replacing the older part of a session with a shorter account of it, so the model is
sent less.

### result guardrail
A guardrail an agent declares for the results of tool calls to an MCP server's tools. It runs
before the model is told the result; a result it refuses is never told to the model, which is
told of an error instead. It does not check the result of one of the agent's own tools.

### MCP server
A program outside the agent that has tools and answers the Model Context Protocol. An
agent lists the ones whose tools it offers the model, each with the credential the platform is to
send it, if it wants one.

## Autonomous agents

### autonomous agent
An agent that is given tasks rather than messages, and iterates on each until its model completes the task with a result or gives up on it, or the task's budget is spent. It is not a request agent.

### task
A durable record of work to be done by an autonomous agent: its task type, its instructions, the tasks it depends on, its status, and, once it is completed, its result. It outlives the agent instance that works it.

### task type
What sort of task a task is: a name, a description, the shape of its result, the rules a result must satisfy and the budget an agent instance may spend on one.

### instructions
What a task asks to be done, in words, as its creator wrote them.

### result
What a completed task produced, of its task type's shape. Of a tool call: what the
model is told when it is over — what the tool answered, an error, or that a person refused it.

### agent instance
One autonomous agent, named by an id its caller chooses, that works its tasks one at a time and queues the rest. It is not an instance of a service.

### iteration
One round of an agent instance's work on a task: a call to the model, and the tools the model asked for. Every iteration is recorded as it happens.

### budget
How many iterations an agent instance may spend on one task of a task type.

### completes
Of the model: says a task is done, giving its result.

### gives up
Of the model: says a task cannot be done, giving its reason.

### assigned
Of a task: given to an agent instance, which has not started it. As a verb, assign: give a task to an agent instance.

### in progress
Of a task: being worked by its agent instance.

### completed
Of a task: ended with a result.

### cancelled
Of a task: ended because a caller cancelled it, or because a task it depends on failed or was cancelled. As a verb, cancel.

### result-rejected
Of a task: completed by the model with a result that a rule or a guardrail refused, and back with the model to try again within the same budget.

### queued
Of a task: assigned to an agent instance that is working another.

### resumed
Of a suspended agent instance: working again from its next iteration.

### terminated
Of an agent instance: stopped for good, its tasks given back unassigned, its id refusing any task from then on. As a verb, terminate.

### idle
Of an agent instance: with no task to work.

### working
Of an agent instance: with a task in progress.

### phase
Which of idle, working, suspended or terminated an agent instance is.

### notification
What an agent instance tells whoever is watching it about something that has just happened: its lifecycle, a task, an iteration, or a warning that a task is struggling. Notifications are not kept.

### watch
Be given an agent instance's notifications as they happen. Whoever does is a watcher.

### watcher
Whoever is watching an agent instance.

### depends
Of a task: may not start until the tasks it depends on are completed.

### unassigned
Of a task: given to no agent instance, so that it may be assigned.

### task status
Where a task stands: "pending" (unassigned), "assigned", "in progress", "result-rejected", or ended
"completed", "failed" or "cancelled". It is not a status, which is how a gRPC call ended.

## Judgments

### judgment
The answers a judgment provider gave to typed questions about a judged content, all asked at once, each with the probabilities behind it.

### judgment provider
What a service asks for judgments: a model that writes nothing and answers typed questions. It is not the model an agent talks to.

Avoid: judgment model

### judged content
The text or value a judgment's questions are asked about.

### question
What a judgment asks of a judged content, declared with an id of its own: a choice question, a score question or a yes or no question.

### choice question
A question whose answer is one of a set of described options, with a probability for each option and a confidence.

### score question
A question whose answer is a place among ordered, described levels, with a probability for each level and a confidence. The answer may lie between two levels.

### yes or no question
A question whose answer is the probability that the answer is yes. It has no confidence.

### option
One of the answers a choice question offers.

### level
One of the ordered, described steps a score question places its answer among.

### probability
A number between 0 and 1 saying how likely a judgment provider holds an answer to be.

### confidence
A number between 0 and 1 saying how concentrated a choice's or a score's probabilities are, as the judgment provider reports it.

### judged guardrail
A guardrail that asks questions of the text going into or coming out of a model, and refuses when an answer crosses a threshold. A judged guardrail that could not ask has refused nothing.

### threshold
The probability, option or level at which a judged guardrail refuses.

### model version
Which version of a judgment provider's model answers a judgment. A service names a fixed one unless its developer names an alias.

### alias
A name for whichever model version a judgment provider currently means by it.

### scripted judgment provider
A judgment provider a test gives its service, which answers from queued answers and standing answers. One with no answer fails the judgment and is never asked again.

### standing answer
An answer a scripted judgment provider gives every judgment that asks its question, without using up its queue.

### definition
What an autonomous agent is declared by: what it is for, how it behaves, its tools and guardrails, its model, and the task types it accepts.

## The console

### sign-up address
Where an installation that creates organizations only through a platform administrator
sends a person who wants one.

### lifecycle
Of a deployed service: the one word the platform reports for where it stands, such as "Ready",
"UpdateInProgress", "Failed", "Paused" or "Suspended". It is not a status, which is how a gRPC call
ended.

### follow
Of a service's logs: be shown each line its instances print as it is read, without
asking again.

### host
A web application that serves the console inside its own look and with its own
sign-in, and may add things of its own beside the console's. It mounts the console package: the
installation's console is one host, and the hosted product's website another.

## Graph deltas

### change
One event, state or deletion of one entity, or one message from a topic, as a consumer is handed it.

### key
What a message is published under. Messages under one key are delivered in order, and a compacted topic keeps the last of them. A message's key is its entity's id unless the message names another; the key does not change which entity the message is about.

### sequence number
Where a change stands in its entity's history: an event's number for an event sourced entity, the state's revision for a key value entity. It only rises, also across a deletion and the entity being created again. A message from a topic has none.

### store
What a sink keeps nodes and relationships in, one element for each element key, applying each delta under the sink's rules: the reference store the platform provides, which holds them in memory, or a store over a database outside the service, which ankka-contrib provides for Neo4j. It is not a service's database.

### element
A node or a relationship, as deltas describe it and a store holds it. Nodes and relationships are named apart, so a node and a relationship may have the same element id.

### element id
The name an element is known by in a store, unique among the nodes or among the relationships.

### node
An element that has labels and properties, such as a cart.

### relationship
An element that runs from one node to another, with a type and properties. Its element key begins "edge:".

### label
A word a node carries to say what sort of thing it is, such as "Cart".

### property
One named value an element carries: text, a number, true or false, or a list of one of those.

### delta
One element's whole state at a version, or a tombstone for it, published as one message under its element key. A delta is not a change.

### tombstone
A delta that marks an element deleted at a version. It marks the element; it does not remove it from the store or from the topic.

### element key
The key a delta is published under: "node:" or "edge:" followed by its element id. Every delta of one element is under one key.

### compacted
Of a topic: kept by the broker as the last message under each key, so that it holds every element's latest delta and not every change.

### pipeline
What reads a topic outside the service and writes what it reads somewhere else, such as into a store: a service with consumers, or several. Its topics are declared on its project.

### ankka-flow
The streaming pipeline platform that ran beside ankka until its capabilities became ankka's, and was retired.

### ankka-contrib
The repository of integrations built on ankka's published SDK that are reusable but not the platform's, such as a store over a database for the sink and a ready image of the sink into it, released on its own cadence against a published ankka version.

### sink
The part of a pipeline that applies the deltas on a topic to a store, each only when its version is newer than the element's there, and refuses a delta that breaks the rules of one. The platform provides it as a component a developer registers in a service with a store; a ready image of the sink into a database is ankka-contrib's, which a member deploys into a project.

## Calling other services

### scripted service
A service the conformance suite plays: it records the call it is given and answers
what it was told to answer.

## Broker

### broker
What holds topics and carries what is published to one to whatever reads it; one
broker serves many services. The installation has one, which the platform provides for every
project; a descriptor may name another instead, and its service then uses that one.

Avoid: Kafka, message bus, queue

### partition
One of the parts a topic is divided into on a broker, each in order. A group's members
divide a topic's partitions between them. A descriptor says how many a topic it declares has. A
topic may be given more and never fewer.

Avoid: shard

### contract
A name and the schema of what a topic carries, declared on the topic and held by the
project, stated by each component that reads the topic or publishes to it with the schema it was
built against, and whose name is carried as the type of every message published to it. Two sides
of a topic must state the declared one.

### schema
The document that says the shape of what a topic carries, held by the project with the
topic's contract, which a member fetches to build against. A message is not checked against it as
it flows.

Avoid: format

### declared broker
A broker a member declares on a project by name, with its address, the shape of its
credential (a certificate, or SASL over TLS) and the project secret holding it, which a component may
name for one topic it reads or publishes to.

Avoid: external broker

### lag
How far behind a topic source is: how many messages the topic holds past the last one
it has handled.

Avoid: backlog, offset lag

### declared topic
A topic a member declares on a project, once, with the partitions it has, which the platform makes
on the installation's broker. Every service of the project reads it and publishes to it by its
name; no service declares it. It is not a declared connection, which is a component saying what it
reads or publishes to.

Avoid: managed topic, provisioned topic

### broker variable
A variable that says where a broker is or how to connect to it. A descriptor names a
broker of its own by giving one; the platform gives them to a service that declares topics. It is
not a platform setting: both programs of a service hosted as a process are given it.

Avoid: Kafka variable

### retention time
*Proposed.* How long a topic keeps a message before the broker removes it: a duration, or
everything, when the broker removes nothing by age. A declaration that gives none is filled from
the installation's default, and the status of the topic says so.

### retention size
*Proposed.* How much each partition of a topic keeps before the broker removes its oldest
messages: a size for each partition, or none.

### cleanup policy
*Proposed.* How the broker removes a topic's messages: "delete", by the topic's retention time and
size; "compact", keeping the last message under each key; or "compact,delete", both.

### tombstone window
*Proposed.* How long a compacted topic keeps a message that marks its key deleted, so that a
reader no further behind than that sees the deletion. It is not a tombstone, which is a delta.

### compaction lag
*Proposed.* How long after a message is published a compacted topic may compact it away: the
minimum compaction lag is the soonest, the maximum the latest. It is not compaction, which
shortens a session.

### minimum in-sync copies
*Proposed.* How many of a topic's copies must hold a message before the broker acknowledges its
publication. It is fixed when the topic is declared, as its copies are.

### bound
*Proposed.* A limit the installation sets on what a topic's declaration may ask: its longest
retention time, which may be none, its largest retention size for each partition, and its most
copies. The control plane refuses a declaration outside a bound, before anything is made on the
broker.

### broker node
*Proposed.* One of the machines the installation's broker runs on, each holding at most one copy
of a partition. A topic with more copies than the broker has broker nodes is reported failed by the
operator. It is not a node, which is an element of a store.

### beginning position
*Proposed.* Where the earliest message the broker still holds on a partition stands, counted from
the first ever published. Above 0, earlier messages are gone; what they were is not knowable.

### earliest retained time
*Proposed.* When the earliest message the broker still holds on a partition was published.

### retention gap
*Proposed.* What a topic source reports, for each partition of its topic, of what the broker no
longer holds: the beginning position, the earliest retained time, and whether messages are gone,
which is so when the beginning position is above 0 or the earliest retained time is later than when
the view first read the topic. A compacted topic is reported as compacted and not as having a gap.

### warning threshold
*Proposed.* The retention time below which a view reading a topic is warned, which the
installation sets: "30 days" as shipped. It is not a threshold, which is a judged guardrail's.

### retention warning
*Proposed.* What a view's status carries when the topic it reads keeps less than the
installation's warning threshold, naming both. A topic that keeps everything, or is compacted,
draws none.

## Sockets

### socket
A connection that a request to a socket route opens and that stays open afterwards,
over which whoever opened it and the route's handler send each other frames until one of them
closes it. The platform carries a socket and does nothing else with it: it keeps no frame and no
record of who holds one open.

Avoid: WebSocket, channel

### socket route
What an HTTP endpoint declares, by a path, to be answered by opening a socket rather
than with one answer. Its ACL is decided once, when the socket is opened.

Avoid: WebSocket route

### frame
One piece of text sent over a socket, by either side.

### closed
Of a socket: ended by one side telling the other that it is ending it, with a close
reason. Either side may close a socket; the platform closes one whose handler has finished or
failed.

### close reason
What the other side is told of why a socket was closed: "finished", "failed", "too
large", "unread", "not text" or, when the instance that holds the socket is stopping, "going away".

Avoid: close code

### cut off
Of a socket: ended without the other side being told, so that it cannot tell a socket
that was ended on purpose from a fault. The opposite of closed.

Avoid: dropped

### thread
One of the limited number of things a machine runs a service's work on at once. A
handler that is waiting holds none.

## Modules

### random bytes
*Proposed.* Bytes nobody could have known beforehand, which the platform gives a module that asks
for them. A module has none of its own.

### interrupted
*Proposed.* Of a module: stopped by the platform part way through what it is doing. A module
cannot be, so the platform stops waiting for one and never stops the module itself.

## Blueprints

### blueprint
A named description of workers and the steps between them, which a service registers
and the platform runs: the input shape of its runs, its workers, its steps, and optionally a
schedule and a run budget. It is held, never deployed, and has no conditions or loops of its own.

### blueprint version
One registration of a blueprint, numbered from 1, never changed. A changed blueprint is
a new blueprint version.

### worker
An agent a blueprint defines by data: instructions, a model, tools, guardrails and a
budget. It is not a component, and not an agent instance.

### action
What a step does once: ask, work, judge or call.

### over
How many times a step does its action, and over what: once; each item of a list an earlier step
gives; each of several workers, optionally chosen by an earlier step; or so many times.

### until
What a step's worker drafts until: a verdict within so many rounds.

### pattern
A common combination of an action, an over and an until, with a name: an ask step, a work step, a
for-each step, a gather step, a judge step, a critique step, a call step.

### call step
A step whose action runs a handler the service registers for blueprints, with what the step reads,
and keeps what it returns.

### ask step
A step in which one worker answers the step's input once, running the tools its model
asks for.

### work step
A step in which one worker iterates on the step's input until its model completes it,
gives up, or spends the budget, as an autonomous agent does.

### for-each step
A step whose action is done once per item of a list, at most the step's limit at once,
and whose result is the items' results in the list's order.

### gather step
A step whose action is done once per worker of several, or so many times by one, each given the
same input at once, and whose result is every result with the worker that gave it.

### judge step
A step whose result is a judgment's answers to typed questions about its input.

### critique step
A step whose worker drafts until a verdict: a critic or a judgment passes the draft, or it goes
back with the reasons, up to the step's number of rounds.

### draft
What the drafting worker of a critique step produces in one round.

### critic
The worker whose verdict passes a draft or returns it with reasons.

### verdict
A critic's or a judgment's decision on a draft: it passes, or it goes back with reasons.

### round
One draft and its verdict, in a critique step.

### run
One carrying out of one blueprint version under a run id: its input, each step's
result, the session of each worker in each step, its usage and its run status. It is not a task.

### run id
The id a run's caller chooses for it; a run started twice under one run id is one run.

### run status
Where a run stands: "running", "waiting for a decision", or ended "completed", "failed"
or "cancelled".

### run budget
How many model calls a whole run may make.

### input shape
The shape an input must have: of a run, as a blueprint declares it; a step declares the
shape of its result the same way.

### schedule
A blueprint's cadence and time zone, from which its due times follow, and whether due
times missed while the service was down start one run or one run per missed period.

### cadence
How often a schedule's due times come: every so many hours or days, or weekly on a day
at a time.

### time zone
A named region whose local time a cadence is written in, such as "Europe/London".

### research digest sample
The sample service that shows blueprints at work: a scheduled watch keeping what its
literature searches find, and a scheduled digest writing a weekly script from it. Its own words
are in its own glossary.

## Timers

### due time
When a timer is to fire, and so when a schedule says a run starts. The platform fires a timer
at its due time or shortly after, never before. A timer that fires again after a failure fires
for the same due time. A handler is told the due time of the timer that ran it.

Avoid: fire time

### period
What lies between one due time and the next. Of a recurring timer, how long that is: a length
of time and nothing else, saying nothing of a time of day or a day of the week. Of a scheduled
run, the span itself, from the previous due time to its own, which is the run's input; periods
meet with no gap and no overlap.

Avoid: interval

### recurring timer
A timer with a period. The platform fires it for one due time after another until it
is cancelled or replaced, and never for a due time that has passed: when several have, it
fires once and goes on from the first still to come. Set again with the same handler and the
same period it is not replaced: it keeps its next due time. A timer with no period fires once
and is removed.

Avoid: repeating timer, periodic timer, cron

### cancel
Of a timer: remove it by its name, so that it does not fire. A handler cancels a timer; the
platform removes one whose handler has run. Setting a timer again does not cancel it.

### backoff
How long the platform waits before it fires again a timer whose handler failed:
"3 seconds" after the first failure, and twice as long after each failure that follows, up to
"30 seconds". It is not a period, and a period never shortens it.

Avoid: retry delay

## Views

### source
What a view or a consumer reads: the events of an event sourced entity, the state of a
key value entity, or a topic. A view may read several, each in order and on its own.

### table
What a view keeps its rows in. Each view has exactly one, its own. It is not any other
table of the service's database.

### row key
What a row is kept under. A view's table holds at most one row for a row key. Unless
the view names another, it is the entity id of the entity the event or the state came from.

Avoid: primary key

### declared query
A query a view declares under a name: a statement, and the names of the values it
takes. A handler asks it by name and gives the values, which are never read as part of the
statement.

Avoid: named query

### recursive query
A declared query that follows from row to row to any depth in one asking, such as from
a row to every row under it. It is still a query of one view's table.

Avoid: recursive read, tree query

### statement
What a declared query says to the database, written by the developer where the view is
declared. The platform checks a statement by the tables it reads when the service starts, and a
service does not start with one that is not a single query of the view's own table.

Avoid: SQL
## Object storage

### object store
Where an installation keeps buckets. The platform makes buckets in the installation's own; a
service may instead have one of its own, outside the platform, which its descriptor gives the
variables of.

Avoid: storage service

### bucket
A named place in an object store where a service keeps objects. The platform makes one for a
service whose descriptor asks for it, names it from the project and the service, and never
deletes it. A service applied again under the name of one deleted is given the bucket it had.

Avoid: container

### object
Something a service keeps in a bucket under a name and reads back by that name, such as a
document a person gave it.

Avoid: file, blob

### storage credential
The credential the platform makes for a service's bucket. It reaches that bucket and no other.
The platform makes it once, gives it to the service's instances when they start, and can never
read it back. It is not a service secret, and it is not an entry of a project secret.

Avoid: access key, secret access key

### signed URL
An address for one object that a service makes with its storage credential and gives to a
browser. Whoever holds it can read that object, or keep it, until the signed URL expires, without
holding the storage credential. It works only while the bucket is reachable from the internet.

Avoid: presigned URL

### Google Cloud Storage
*Proposed.* Google's object store, which an installation in Google's cloud may keep its buckets in
instead of Garage. A bucket in it is reachable from the internet by anyone holding a signed URL,
whether or not its descriptor asked, and by nobody else; it keeps every version of an object; and
its name is shared with every other customer of Google's, so the provider names it and reports the
name, and nothing derives it.

Avoid: GCS

### Garage
*Proposed.* The object store the platform runs inside an installation, and the one a local platform
has. A bucket in it holds one version of each object, is named from the project and the service,
and is reached from the internet only when its descriptor asks.

### provider
*Proposed.* The program an installation deploys beside the operator to make what a cloud's object
store needs for a bucket: the bucket, the storage account, its grant and the storage credential.
It reaches the cloud as its own workload identity and holds no key of the cloud's. The operator
asks it for a bucket and reads what it reports, and never reaches the cloud itself.

### storage account
*Proposed.* The identity in Google Cloud that the provider makes for one service with a bucket in
Google Cloud Storage, granted on that bucket and on nothing else. The service's storage credential
belongs to it, and the service's workload identity is it.

Avoid: service account, Google account

### workload identity
*Proposed.* What a deployed service is, to Google Cloud, without holding any credential: its
storage account. A service that reaches its bucket as its workload identity needs no storage
credential, and can make no signed URL.

### location
*Proposed.* Where in Google's cloud a bucket keeps its objects, fixed when the bucket is made and
reported in the status. The installation names one for its buckets; a project may name its own,
for the buckets made for it from then on.

### retention policy
*Proposed.* A setting of a bucket in Google Cloud Storage that refuses to delete an object younger
than an age. The platform sets none on any bucket and offers no setting that does: holding a
document for as long as a rule requires is the service's own to do, and a deletion is never
refused on account of an object's age.

### noncurrent version
*Proposed.* An object as it was before it was overwritten or deleted, which a bucket in Google
Cloud Storage keeps and a service reads back, until every version of the object is deleted. A
bucket in Garage keeps none.

### KMS key
*Proposed.* A key the installation holds in Google Cloud's key management and names so that every
bucket the provider makes is encrypted with it rather than with Google's own. It is not a secret
key, which a service's secret store encrypts with.

### move
*Proposed.* Copying every object of one service's bucket in Garage into a bucket made for it in
Google Cloud Storage, so that the service reads and keeps objects there once it is next
restarted. A member asks for it; it checks every object on both sides; it finishes if it is asked
for again after stopping part way; and it leaves the bucket in Garage as it was.

Avoid: migration

### read-only credential
*Proposed.* A storage credential that reads a bucket and cannot keep, change or delete an object in
it. A move gives a service one while it copies what changed and checks every object, and the
service's status says that its storage is moving; the service is given a storage credential that
writes again when the move ends, on whichever object store it ends on.

## Cross-project access

### grant
*Proposed.* What a project holds to let one grantee reach one target of its own. An owner of the
project's organization makes it, as data, and no code of either side changes for it. A grant
within that organization is accepted when it is made; one to a grantee of another organization is
pending until an owner there accepts it. Only an accepted grant opens anything; an ended grant is
never reopened, and granting again makes a new one. A grant is identified, while it is live, by
its grantee and its target, so the same one made twice is one grant.

Avoid: access grant, ACL entry

### grantor
*Proposed.* The project that holds a grant, and the owners of its organization, who make,
withdraw and revoke it.

### grantee
*Proposed.* Who a grant names: a service of another project, or a registered machine. The
organization it belongs to is the one a cross-organization grant is offered to, and whose owners
accept, decline or relinquish it. It is not a principal, which is what an authenticator
established of a request.

### target
*Proposed.* What one grant opens: one route or one method of one of the grantor's services, one of
the grantor's declared topics to consume, to produce to, or both, or the right to ask for the
erasure of the grantor's data subjects. Never more than one, and never a prefix or a pattern.

### consume
*Proposed.* Of a topic grant: lets the grantee read the topic, under a group of its own. It gives
no position: where the grantee starts reading is its own start position.

### produce
*Proposed.* Of a topic grant: lets the grantee publish to the topic.

### in effect
*Proposed.* Of a grant: accepted, and reached where it is read, so that its grantee reaches its
target. A grant that is not in effect says why: "pending", "declined", "withdrawn", "revoked",
"relinquished", "route not seen", "route not grantable", "rollout needed" or "broker not exposed".

Avoid: active grant, live grant

### accept
*Proposed.* Of an owner of the grantee's organization and a pending grant: take it, so that it is
in effect.

### decline
*Proposed.* Of an owner of the grantee's organization and a pending grant: refuse it, so that it
never takes effect.

### withdraw
*Proposed.* Of the grantor and a pending grant: take it back before it is answered, so that it is
no longer offered.

### relinquish
*Proposed.* Of an owner of the grantee's organization and an accepted grant: give it up, without
the grantor, so that it opens nothing again.

### granted caller
*Proposed.* A calling workload that holds a grant in effect on the route or the method it is
calling: a service of another project, or a registered machine. An ACL that admits granted callers
is what makes a route grantable; it admits no caller without a grant.

Avoid: granted matcher

### grantable
*Proposed.* Of a route or a method: its ACL admits granted callers, so that a grant on it opens
it. A grant on one that is not is accepted and reported as "route not grantable"; a web-hosted
service's routes are never grantable.

### registered machine
*Proposed.* A machine outside the installation that an owner registered on an organization, which
proves which machine it is with a machine token. It holds no grant; grants name it. On the broker
it is a credential of its own, with no topic until a grant gives it one, and it is kept when the
machine is deleted. It is not a member, and it is not a deploy token.

Avoid: external machine, outside machine, partner machine

### client id
*Proposed.* The name a registered machine gives the token route when it asks for a machine token,
together with its client secret.

### client secret
*Proposed.* What a registered machine proves itself with to the token route: shown once, when the
machine is registered, and never again; the control plane keeps only a digest of it. It is not a
secret, which a project keeps for a descriptor, nor a service secret.

### machine token
*Proposed.* What the control plane signs for a registered machine, for its client id and client
secret: a token that names the machine and nothing else, lives fifteen minutes, and is checked
against the control plane's keys without asking it. It carries no grant, so a grant changes
without a new machine token and a revocation never waits for one to expire. On a route it is
sent as any token is; on the broker it is what the registered machine proves itself with.

Avoid: client credentials token

### token route
*Proposed.* Where a registered machine asks the control plane for a machine token. A client id
that asks more often than the installation allows is refused there for a while.

### byte rate
*Proposed.* How many bytes a second the broker lets a registered machine publish, and how many it
lets it read, from the installation's defaults; an owner may set one machine's within the
installation's ceiling. It is not a quota, which counts projects, services and instances.

### throttled
*Proposed.* Of a registered machine: made to wait by the broker because it has reached its byte
rate. What it reads still arrives, later; the installation's services are never throttled for it.

## Backups and recovery

### project database
*Proposed.* What holds every provisioned database of one project's services, and is backed up and
restored as a whole. A project has one until a restore makes another beside it; its status names
the one each service is on.

Avoid: Postgres cluster, database cluster

### backup target
*Proposed.* Where an installation's backups go: its object store, named once for the installation.
With none named, nothing is backed up and every status says so.

### backup bucket
*Proposed.* A bucket the platform makes for one project's backups, and one each for the database of
the control plane and the platform's other stores. The project database and the platform reach it;
no storage credential does. The platform never deletes it.

### archive
*Proposed.* Every write a project database makes, written to its backup bucket as it is made, so
that the project database can be restored to any moment the archive reaches. As a verb: write to
it. How far behind the project database the archive is, is the writes a loss would lose.

Avoid: WAL, write-ahead log

### base backup
*Proposed.* A whole copy of a project database at one moment, taken every day. A restore starts
from the latest one before its moment and reads the archive from there.

### backed up
*Proposed.* Of a project: its project database has a base backup and an archive in its backup
bucket, and, where the installation requires a copy outside the failure domain, its latest base
backup has one.

### retention window
*Proposed.* How far back a project can be restored to: 30 days as shipped, set for the installation,
and a project may set its own. A base backup or archive older than it is removed only once a newer
base backup has completed.

### restore
*Proposed.* A project database made anew at a moment in the retention window, beside the current
one, from the latest base backup before the moment and the archive up to it. It changes the current
project database not at all; a service reaches it only by a switch. As a verb: make one. It is not a
roll back, which brings back a descriptor and no data.

Avoid: point-in-time recovery, PITR

### restore point
*Proposed.* The moment a restore was made at.

### switch
*Proposed.* Moving one service of a project from the project database it is on to another of the
project's, a restore or one it left, at the service's next rolling update. An owner switches one
service at a time; the project database the service leaves is kept, and switching back is the same
action.

Avoid: migrate, cut over, failover

### line of history
*Proposed.* One project database's archive, from its making or from the first switch to it. A
restore a service was switched to begins a line of its own; every earlier line stays restorable
within its retention window.

Avoid: timeline

### message id
*Proposed.* What a message published from a journal event carries to be told from every other
message: made from the event's line of history, its entity and its sequence number, so that an
event published again after a restore carries the one it carried before, and an event recorded
after a restore never carries one an earlier message carried. It protects only a reader that
deduplicates by it.

### journal
*Proposed.* The table a service's database keeps its entities' events in: the record a restore
takes back to the restore point.

### read position
*Proposed.* How far a view or a consumer has read of a source, as the service's database records
it. A restore takes it back with the journal; a group's position on the broker is not taken back.

Avoid: offset

### rehearsal
*Proposed.* A restore into a project database made for it, apart from the project's own, which no
service is switched to: checked as a restore is, timed, and then removed. Its report is kept on the
project. The operator may remove a project database made for a rehearsal and no other.

Avoid: drill, dry run

### time to live
*Proposed.* How long a project database made for a rehearsal may exist before the platform removes
it, whatever became of the rehearsal: 24 hours as shipped.

Avoid: TTL

### primary
*Proposed.* The one copy of a project database that takes its writes. A project database runs a
primary alone until the project asks for replicas.

### replica
*Proposed.* A further copy of a project database, kept up to date from the primary, and promoted
when the primary is lost. A project asks for how many it has.

Avoid: standby

### promoted
*Proposed.* Of a replica: made the primary, with no member doing anything, when the primary is
lost. The services of the project connect to it on their own. A promotion does not change the line
of history.

### synchronous
*Proposed.* Of a project database with replicas: a write is not acknowledged until a replica holds
it as well as the primary, so that losing the primary loses no acknowledged write.

### restore marker
*Proposed.* What a restore of the database of the control plane ends by writing. The control plane
reads it at its next start and holds its projection until a platform administrator releases it,
which removes the marker.

### projection
*Proposed.* The control plane's making of the cluster into what it recorded. Held, it changes
nothing in the cluster and lists every service, declared topic and project that differs from what
it recorded.

Avoid: reconciliation loop

### erasure
*Proposed.* A request that everything recorded about one person be made unreadable, as feature 042
describes it.

### erasure log
*Proposed.* The control plane's record of every erasure filed in the installation, of which it keeps
a copy in a bucket. A control plane restored to before an erasure brings its erasure log up to date
from that copy before it can be released.

### Garage
*Proposed.* The object store the platform installs for an installation that names no other: on one
machine as shipped, or on three with each object on every one of them.

### secondary store
*Proposed.* An object store outside the cluster to which every bucket of the installation's Garage
is copied again and again, the services' and the backup buckets alike. An object deleted from
Garage is deleted from it at the next copy, so it never holds what the installation has erased.

Avoid: mirror, offsite

### failure domain
*Proposed.* What is lost together: the cluster, with every machine and volume in it. A backup kept
in the failure domain of what it backs up is not a backup of it.

### volume
*Proposed.* The disk one machine keeps a project database or Garage's objects on. Losing it loses
what was on it, unless a replica, another machine or a backup holds it too.

Avoid: disk, PVC

## Erasure

### data subject
*Proposed.* The person a personal field is about, named by an id the domain chooses, such as
"player/8c1f", which is readable wherever it is held and survives an erasure. A data subject is of
one project: a person known to two projects is two data subjects. It is not a subject, which is an
issuer's id for the person a token names.

### personal field
*Proposed.* A field of an event, a state, a row or a message that a service marks as about one data
subject. Every store holds it only encrypted, as a personal envelope. A field that is not marked,
such as an amount, an id or a time, is held as it was written, and the platform cannot know that
it is about anyone.

### personal envelope
*Proposed.* What every store holds a personal field as: the data subject readable beside the
field's value encrypted under that data subject's subject key. It is the same in every language and
in every store, and nothing but the code that reads and writes personal fields looks inside it.

### subject key
*Proposed.* The key one data subject's personal fields are encrypted under, in one project. It is
made with the first write of one of them, whichever service or instance writes first, kept by the
keyring, destroyed by an erasure, and never replaced. It is not the secret key, which encrypts a
service's secret store, and not an issuer's keys.

### keyring
*Proposed.* The part of the platform that keeps every subject key and lookup key of an installation,
outside every service's database and every project's backup. It gives a subject key only to a
service of the data subject's project, or to a holder of a grant that allows decryption, and it
records every request it refuses. No subject key leaves the installation.

### lookup token
*Proposed.* What a row of a view carries beside a personal field marked for lookup, so that a
declared query can match the field's value without reading it: a keyed hash under the project's
lookup key, which the keyring holds. It is removed from every row of a data subject by an erasure.

### lookup key
*Proposed.* The key of one project that its lookup tokens are made with. The keyring keeps it; it is
not a subject key.

### erasure
*Proposed.* Destroying a data subject's subject key, so that every personal field of that data
subject reads as erased wherever it is held, and running what each service of the project does for
the data subject of its own. Nothing written is removed: the personal envelopes stay, unreadable.
As a verb, erase.

Avoid: shredding, crypto-shredding

### erased
*Proposed.* Of a personal field: read as having no value, because its data subject's subject key
was destroyed. It is a value, not a failure: an entity is recovered, a view is rebuilt and a
consumer is handed a message with erased in a personal field. Of a data subject: with its subject
key destroyed, so that no personal field can be written for it again.

### erasure request
*Proposed.* Asking the platform for the erasure of one data subject in one project: who asked, a
not-before date and a reason if it is held, a correlation id if one was given, and where it
stands: held, withdrawn, applied. An applied erasure request records when the subject key was
destroyed, each service's completion and when the erasure became final. The platform never carries
one beyond its project.

### not-before date
*Proposed.* The date before which an erasure request is not applied. The domain chooses it, for a
hold the law puts on the data; the platform keeps it and applies the erasure request when the date
has passed, without anyone acting. Only an owner may override it, with a reason that is recorded.

### withdrawn
*Proposed.* Of a held erasure request: taken back before its not-before date by whoever asked for it
or by a member, so that nothing is destroyed. An applied erasure request cannot be. As a verb,
withdraw.

### completion
*Proposed.* What an erasure request records for one service of its project: when the service had
dropped the subject key, redacted its rows, removed its lookup tokens, ended its sessions of the
data subject and run its erasure handler to the end, and what the erasure handler reported.

### erasure handler
*Proposed.* The one handler a service may register to do its own part of an erasure, such as
erasing the data subject's objects. It is run with the data subject on every application of an
erasure request in its project, and again on each later application, so it must be safe to run
again. What the platform does of its own does not wait for it.

### erasure log
*Proposed.* The record of every applied erasure of an installation, written before any subject key
is destroyed and kept in two places outside the keyring's database. The keyring applies it before
it answers anyone after its database is restored, and a service applies its project's entries to
its own tables before it is ready after a restore of its project's database.

### erasure certificate
*Proposed.* What a member fetches for an applied erasure request, to give the data subject: the
erasure request, the data subject, who asked for it, each service's completion and when the
erasure became final. It holds no personal field.

### correlation id
*Proposed.* An id whoever asks for an erasure request may give it, so that erasure requests in
different projects for one person can be listed together. The platform reads nothing into it.

### grant
*Proposed.* A project's statement that a service of another project, or a machine outside the
installation, may do one thing with what is its own: read one of its topics, read one of its topics
with decryption, or ask for an erasure in it. A project revokes a grant, and from then on nothing
is admitted by it. It is defined by cross-project access (spec 040); this is what erasure needs of
it.

### decryption
*Proposed.* What a grant on a topic may allow beyond reading it: the holder's reads of the personal
fields on that topic are given their values. A service in another project is given the subject key;
a machine outside the installation is not, and asks the keyring to decrypt each field for it, which
the keyring records against the machine and the grant. Without it, every personal field on the
topic is read as erased.

### subject prefix
*Proposed.* Where a service keeps the objects of one data subject in its bucket: under the name
"subjects/", the data subject and "/". An erasure handler asks the platform to erase every object
under it, every version where the object store keeps versions; an object kept outside it is not
erased.

### soft-delete window
*Proposed.* How long an object store that keeps every version of an object still holds a deleted
one before it is gone. The erasure of a data subject's objects becomes final when it has passed.
It is defined by object storage on Google Cloud (spec 039).

### restore
*Proposed.* Bringing a database back from a backup to what it held at an earlier point: a service's,
the control plane's or the keyring's. It is defined by backup and recovery (spec 041); this is what
erasure needs of it. As an adjective, restored.

### switched
*Proposed.* Of a service: given a restored database in place of the one it had (spec 041). A
service that is switched is not ready until it has applied the erasure log to the restored
database.

### journal
*Proposed.* Where a service's database keeps every event its event sourced entities recorded, in
order. Nothing is ever removed from it.

## Everyday words

scripted, network, key, features, twelve, thirty, forty, per, week, weeks, weekly, Sunday, Sundays, clock, clocks, previous, past, remaining, titles, identifier, row, read, reads, reading, show, shows, shown, write, written, language, every, same, connected, whose, since, started, nothing, handle, handles, serve, serves, publish, publishes, source, outside, only,
other, none, with, without, ask, asks, asked, left, leave, leaves, out, until, marked, marks, focus,
focuses, focusing, what, else, make, makes, made, through, time, times, attributed, counted, count,
counts, apart, together, added, wait, waits, waiting, stopped, longer, takes, finishes, run, runs,
ran, any, different, many, once, ends, reaches, set, fires, last, passes, long, restarts, restarted,
restart, however, opens, opened, exactly, named, name, names, running, machine, here, beyond, limit,
most, all, then, given, person, exist, too, old, report, have, some, reader, published, about,
describes, says, say, inside, starts, answers, answered, answer, can, called, declares, declared,
quantity, part, is, are, does, did, how, where, who, gives, start, cannot, calls, calling, declare,
parts, end, ended, served, sent, found, denies, allows, admits, states, upgraded, version, new,
connects, yet, reported, hosted, applies, differ, sets, tells, instead, minutes, second, within,
stopping, stops, finish, goes, away, registered, produces, produced, fewer, records, lists, offer,
both, two, one, each, after, before, under, more, than, must, may, itself, another, own, built,
building, believed, establishes, established, decides, replaced, alone, test, sends, accepted,
reached, reason, why, web, page, alike, arrives, ending, holds, told, unfinished, chooses, sending,
opts, opted, into, tool, still, send, tell, which, keep, keeps, come, comes, came, listen, listens,
listening, printed, allow, said, never, hold, tests, use, uses, used, give, gain, gains, change,
changes, changed, take, taken, signs, signed, for, from, that, listed, carries, carrying, not, no,
value, expired, valid, fetched, fetch, unreachable, been, has, had, having, rather, problem,
problems, twice, attached, ignored, settings, its, their, this, these, those, it, them, they, of,
and, or, a, an, the, to, in, on, by, as, at, up, down, so, if, everyone, while, when, again, go,
going, do, done, fails, failed, failure, be, being, was, were, now, also, later, over, other's,
issuers', service's, email, address, whether, dates, date, slow, arrive, arriving, well, less, first,
installation's, organization's, type, admitted, asking, reachable, kept, keeping, remove, removes,
removed, gave, list, become, becomes, values, nowhere, afterwards, fail, larger, rule, break, breaks,
slash, holding, newly, exists, try, tries, there, such, present, record, recorded, delete, deleted,
back, distinct, between, naming, starting, longest, permitted, accepts, declaring, already,
delivered, missing, honoured, earlier, higher, lower, positive, whole, number, during, beside, far,
looks, default, bounded, upgrading, empty, nobody, created, creation, because, reserved, sees, lost,
cart, item, moment, find, form, place, warning, active, replacement, join, joins, joined, cut, side,
went, held, unchanged, need, someone, anything, copy, everything, always, hour, grow, connection,
connections, connect, credential's, refuses, exchanged, password, timers, due, rows, wrote, socks,
redirected, predicted, seconds, checks, checking, checked, check, verify, verifies, unchecked, risks,
moved, installs, installed, installing, enforce, network, talk, talks, showing, cluster's, gateway's,
issuer's, control, plane's, member's, developer's, machine's, ports, needs, adds, create, act,
issued, issue, trust, trusts, against, security, protect, way, builds, depend, depending, prepared,
prepares, prepare, program, programs, edited, order, writes, carry, creates, labelled, today,
today's, attributes, invites, renames, deletes, lifetime, stating, stated, ninety, expires, expire,
expiring, succeeds, released, pushes, pushed, push, tags, tagged, commit, hand, declines, username,
public, private, plainly, wraps, sign, line, lines, recent, paused, cost, unknown, prices, duration,
oldest, outcome, body, client, monitoring, collects, draws, provided, follows, refers, copied,
belongs, days, accounts, ever, took, among, forms, knows, confirms, acting, anyone, get, revoked,
across, fourth, replied, deletion, nested, stays, speaks, speak, drives, kit, JVM, Docker, Rust,
toolchain, Scala, Python, TypeScript, items, carts, profile, workflow's, refuse, recovery, recovers,
recover, failing, passing, behaviour, mid-command, exercised, byte, bytes, compatible, install,
imported, package, lacks, function, broken, fixed, error, handed, steps, moving, moves, see, seen,
retried, memory, samples, tested, contents, differs, rules, languages, whichever, loads, load,
replace, schema, spent, work, approve, begins, warned, happens, happened, cites, cite, nearing,
words, text, team, teams, ticket, shape, included, include, chosen, characters, Akka's, above,
whatever, total, therefore, talking, stood, stay, replies, remains, remain, rejected, rate,
overrides, override, overloaded, navigation, moderate, lie, lead, invented, highest, guides, guide,
forwards, figure, explains, differences, difference, deliberate, delegate, defeat, deactivated,
activated, copies, configure, condition, computed, author, agree, renewing, renewed, browser's,
administrator, timed, elsewhere, following, drawn, look, addition, hides, rename, renamed, invited,
enables, disables, confirmed, detail, milliseconds, assistant, absence, setting, absent, something,
anyway, identity, proves, known, belong, someone's, themselves, membership, wherever, loading, begin,
begun, returning, receives, example, cloud, everywhere, people, development, return, turned,
JavaScript, alters, choice, several, hands, reference, shopping, three, perhaps, single, current,
plain, describe, describing, checkout, checkouts, applied, caught, uninterrupted, computes, outranks,
rise, greater, neither, replaces, documented, documentation's, needed, writer, closes, offers,
offered, signing, applying, foot, hidden, scroll, scrolls, sideways, pauses, saying, overview, open,
reloaded, audited, visible, keyboard, brightest, point, least, brighter, shipped, colour, accord,
blur, opaque, readable, border, outline, forces, edge, clipped, below, facts, controls, prefers,
preference, dark, light, fetches, mounts, mounted, small, brightness, ratio, centre, screen, bright,
enough, front, width, would, choose, whoever, clear, declaration, large, unread, crosses, older,
quiet, requires, requiring, working, day, week, length, decision, note, move, beginning, deep,
access, acknowledged, acknowledges, age, ago, authority, became, bring, brings, brought,
certificate, comment, compacts, decrypt, decrypted, decrypts, defaults, derived, destroy, destroyed,
destroys, entries, equal, fast, filed, filled, final, future, gone, grant, granted, grants, largest,
lose, losing, maximum, minimum, minute, newer, newest, off, often, ordinary, overwrite, overwrites,
overwritten, parallel, passed, past, permission, promotion, publishing, reach, rebuilt, redacted,
registers, rehearse, rehearses, restore, restored, returns, right, SASL, share, shares, union,
withdraw, withdraws, withdrew, year
