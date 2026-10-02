# Glossary

The words the platform's features use, each in exactly one sense. A term marked *Proposed.* has
still to be settled by `/speckit-clarify`; none is at present. The platform's established words
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
A handler that may change an entity's state.

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

## Observability

### trace
Everything the components of a service did for one request or one event, and how they were nested.

### request
Something asked of a service from outside it, which an endpoint serves by one of its routes, or
the process of a web-hosted service serves.

## Deploying

### platform
What runs deployed services and is told what they should be: the installation a member's projects
are in.

### local platform
A platform on a developer's own machine.

### descriptor
What a member writes to say what a service should be: its image, its hosting, its environment, its
size and how many instances it has.

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

### status
What the platform last saw of a deployed service, with one word for the whole: "Ready", "Paused",
"Failed" and the others.

### ready
Of an instance: able to be sent requests. Of a service: every instance it should have is ready.

### expose
Make a deployed service reachable from the internet at its hostname. A service is private until
it is exposed.

### exposed
Of a service: reachable from the internet at its hostname.

### hostname
The address an exposed service answers at. The platform chooses it; a member cannot.

### internet
Everyone and everything outside the platform. A request from the internet reaches a service only
through its hostname.

### access rule
What a service's endpoint says about who may call it. Exposing a service changes who can reach it,
never whom its access rule admits.

Avoid: ACL

### admit
Of an access rule, or of a web-hosted service's descriptor: allow a call or a request from the one who
made it.

### secret
A named value the platform keeps for a project, which a descriptor's variable can be taken from.

### certificate
What a service proves who it is with, to the services it calls and the ones that call it.

### database
Where the platform keeps what a service's entities, views and workflows know.

### logs
What a service's instances printed.

### organization
The group of members that a project belongs to.

### quota
How many projects, services and instances an organization may have.

### pause
Stop every instance of a deployed service and keep the service.

### resume
Start a paused service's instances again.

### scale
Change how many instances a deployed service has.

### sample
A service the platform publishes as an example, such as the shopping cart.

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

### process
In a web-hosted service's instance, the program from the service's image. It serves requests that
the proxy passes to it and holds no certificate. It is what the service's developer wrote. The
same word names the developer's program in a service whose image is written in another language
and run beside the platform's own program.

Avoid: app, application server

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

Avoid: route, rewrite, proxy rule

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
What a person on the internet sends requests with and is shown the answers in.

## Everyday words

read, reads, reading, show, shows, shown, write, written, language, every, same, connected,
whose, since, started, nothing, handle, handles, serve, serves, publish, publishes, source,
outside, only, other, none, with, without, ask, asks, asked, left, leave, leaves, out, until,
marked, marks, focus, focuses, focusing, what, else, make, makes, made, through,
time, times, attributed, counted, count, counts, apart, together, added, wait, waits, waiting,
stopped, longer, takes, finishes, run, runs, ran, any, different, many, once, ends, reaches,
set, fires, last, passes, long, restarts, restarted, restart, however, opens, opened, exactly,
named, name, names, running, machine, here, beyond, limit, most, all, then, given, person,
exist, too, old, report, have, some, reader, published, about, describes, says, say, inside,
starts, answers, answered, answer, can, called, declares, declared, quantity, part, is, are,
does, did, how, where, who,
send, sends, sent, sending, tell, tells, told, which, keep, keeps, come, comes, came, listen,
listens, listening, printed, allow, allows, own, said, itself, never, hold, holds, test, tests,
use, uses, used, give, gives, gain, gains, change, changes, changed, take, takes, taken
