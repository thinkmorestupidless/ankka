# Glossary

The words the platform's features use, each in exactly one sense. A term marked *Proposed.* has
still to be settled by `/speckit-clarify`: those under *Topic sources*, at present. The platform's established words
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
One unit of a workflow's work. A step may call other components.

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
Of a deployed service: reachable from outside the cluster, at its hostname.

### hostname
Where an exposed service answers from outside the cluster, for HTTP and for gRPC. The platform
derives it; nobody chooses it.

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
about who may call it. It denies all, allows all, admits only the workloads it names, or asks an
authenticator.

Avoid: access rule

### authenticator
An ACL that reads a call and answers one of four things: allow, establishing a
principal; unauthenticated; forbidden; or unavailable, when it cannot tell.

### principal
Who a call came from, as an authenticator established it.

### calling workload
The workload a call to an endpoint came from, as the platform established it: a
service of a project, the gateway, or the local caller. It is read from the certificate and never
from what the call says.

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
it is exposed.

### internet
Everyone and everything outside the platform. A request from the internet reaches a service only
through its hostname.

### admit
Of an ACL, or of a web-hosted service's descriptor: allow a call or a request from the one who
made it.

### secret
A named value the platform keeps for a project, which a descriptor's variable can be taken from.

### database
Where the platform keeps what a service's entities, views and workflows know, and the service's
secret store. Each deployed service has its own, provisioned by the platform or supplied by the service.

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
Of a descriptor: a short value that two descriptors share exactly when they state the
same things.

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
Where a service keeps its service secrets: in its database, and apart from everything
its components know. It is not a component, and nothing a component records or a view is built
from ever holds what is in it. An entity and a view are given none; every other component of the
service keeps and reads through the same one.

Avoid: vault

### service secret
A named value a service keeps in its secret store while it runs and reads back by that
name, such as a credential a person gave it. The value is text, never empty, and no larger than
the secret store's limit. It belongs to the one service that kept it; another
service cannot read it. It is not a project secret, which a member sets before a service starts.

Avoid: runtime secret

### secret key
What a service's secret store encrypts its service secrets with. Each service has its
own. The platform makes one for a deployed service unless its descriptor gives one, and keeps it
when the service is deleted; on a developer's machine the developer gives one. It is a platform
setting. It is not an entry of a project secret, and it is not an issuer's keys.

Avoid: encryption key, master key

### encrypted
Of a service secret as the database holds it: unreadable by anyone who does not have
the service's secret key.

### project secret
A named set of entries the platform keeps for a project, which a descriptor's variable
can be taken from. A member sets and removes its entries; the platform gives its values to a
service's instances when they start and shows them to nobody. A project secret with no entry left
is no longer listed.

Avoid: static secret

### entry
One named value of a project secret. A descriptor's variable is taken from one entry.

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
Of a deploy token: end it, so that no request with it is admitted again.

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
Who did what to a service, and when, as the control plane recorded it: what was done, at which
generation, by whom and when, newest first. It keeps the most recent and forgets the rest. It is
not what a service's instances printed, and not what its entities recorded.

Avoid: audit log

## Organizations

### invitation
An email address an owner has asked to make a member of an organization, with the role
it will have. It is pending until a person whose verified email is that address claims it, and an
owner may revoke it.

Avoid: invite (as a noun)

### pending
Of an invitation: neither claimed nor revoked.

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
The database outside the service that a sink keeps nodes and relationships in, one element for each element key. It is not a service's database.

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
What reads a topic outside the service and writes what it reads somewhere else, such as into a store. It declares the topics it owns.

### sink
The part of a pipeline that applies the deltas on a topic to a store, each only when its version is newer than the element's there, and refuses a delta that breaks the rules of one.

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

## Timers

### due time
When a timer is to fire. The platform fires a timer at its due time or shortly after,
never before. A timer that fires again after a failure fires for the same due time. A handler
is told the due time of the timer that ran it.

Avoid: fire time

### period
How long after one due time a recurring timer's next due time is. It is a length of
time and nothing else: it says nothing of a time of day or a day of the week.

Avoid: interval, schedule

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

## Everyday words

read, reads, reading, show, shows, shown, write, written, language, every, same, connected, whose,
since, started, nothing, handle, handles, serve, serves, publish, publishes, outside, only,
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
comment
