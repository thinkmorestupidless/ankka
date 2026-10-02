# Glossary

The words the platform's features use, each in exactly one sense. A term marked *Proposed.* has
still to be settled by `/speckit-clarify`; none is at present. The platform's established words are defined as
`docs/reference/glossary.md` defines them for the people who build on it. The shopping cart sample
has a glossary of its own, in `samples/shopping-cart/`.

## The platform

### platform
What hosts services: on a developer's machine, and in a cluster for the services deployed to it.

### service
A set of components registered together and run as one.

### component
A unit of a service that the platform hosts: an event sourced entity, a key value entity, a view,
a consumer, a workflow, a timed action, an agent or an endpoint.

### endpoint
A component that turns requests from outside the service into calls to its components.

### HTTP endpoint
An endpoint that serves HTTP: it turns each request to one of its routes into calls to
components. Until gRPC endpoints, the only kind of endpoint.

### route
One kind of request an endpoint serves, named by its method and its path with the changing parts
left as names: "POST /carts/{cartId}/items".

### handler
A part of a component that the platform runs: a command, a step, a timed action's action, an
agent's handler, or what a gRPC endpoint declares for a method. Each is declared with a name.

### request
Something asked of a service from outside it, which an endpoint serves by one of its routes or
one of its methods.

### instance
One running copy of a service.

### cluster
The machines a deployed service's instances run on.

### deployed
Of a service: running in a project on the platform, not on a developer's machine.

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
only to an instance that is ready.

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

### documentation
What the platform publishes for the people who build on it.

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
route's method ("POST") is part of the route's name and is always written inside it.

Avoid: rpc, operation

### call
One component asking another to run a handler, or one service asking another for something. A
developer or a test asking a service for something is a call too.

### status
How a gRPC call ended, as whoever called is told: "ok", or the name of one kind of
refusal or failure. Every gRPC call ends with exactly one.

Avoid: status code, error code

### message
The words a status carries to say why the call ended as it did.

### metadata
The names and values sent with a gRPC call beside its request.

Avoid: headers

### stream
A request or an answer that arrives a part at a time. A call is one call, however many parts its
streams carry.

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

### refusal
A deliberate "no": a handler's to a call, or the platform's to a person who may not have what they
asked for. A refusal is the service working, not the service failing.

### refused
Of a handled call: it ended in a refusal. Of a person: given a refusal.

### failed
Of a handled call: something went wrong in the handler. Of a deployed service: reported as not
having become ready in the time it is given.

## Observability

### trace
Everything the components of a service did for one request or one event, and how they were nested.

### root
Of a trace: what the rest of the trace was done for, and is nested under.

## Everyday words

read, reads, reading, show, shows, shown, every, same, only, other, none, with, without, ask,
asks, asked, what, make, makes, made, time, times, run, runs, ran, any, name, names, named,
running, machine, all, then, given, gives, says, say, starts, start, started, answers, answered,
answer, can, cannot, called, calls, calling, declares, declare, part, parts, is, are, does, did,
how, where, who, whose, end, ends, ended, serve, serves, served, sent, found, denies, allows,
admits, states, upgraded, version, new, restarted, connects, yet, reported, hosted, applies,
differ, sets, tells, variable, instead, minutes, second, within, stopped, stopping, stops, finish,
goes, away, registered, takes, produces, produced, fewer, records, marked, lists, offer, both,
two, one, each, last, after, before, under, nothing, more, than, must, may, itself, another, own,
built, building, published, describes, about, believed, establishes, established, decides, set,
added, replaced, alone, once, long, test, sends, accepted, reached, reason, why, web,
page, outside, alike, arrives, ending, holds, waits, told, unfinished, chooses, sending,
opts, opted, into, tool, still
