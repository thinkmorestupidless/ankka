// Package ankka is the Go SDK for ankka, a serverless platform for agentic AI on the actor model.
//
// A service written with it is a Go program that declares its components (entities, views,
// consumers, workflows, timed actions, agents, endpoints) as values, hands them to NewService, and
// calls Start. The platform's own program, the sidecar, runs beside it, owns the journal, the
// cluster and HTTP, and asks the program to run its handlers over the sidecar protocol. Handlers
// return effects: inert descriptions of what should happen, which the sidecar interprets.
//
// The test kits are in the package github.com/thinkmorestupidless/ankka-go/testkit.
package ankka

// ProtocolVersion is the sidecar protocol this SDK speaks, declared in discovery.
const ProtocolVersion = "1.22"

// SDKName is how this SDK names itself to the sidecar, and how a deployed service says which SDK
// built it.
const SDKName = "ankka-go"
