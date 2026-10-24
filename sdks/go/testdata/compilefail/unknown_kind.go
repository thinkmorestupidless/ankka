//go:build ignore

// A component is one of the kinds the SDK declares: a type of the service's own, whatever methods it
// has, is not a declaration the platform can host, so handing one to NewService does not compile.
package main

import ankka "github.com/thinkmorestupidless/ankka-go"

type Ledger struct{}

func (Ledger) ID() string { return "ledger" }

var _ = ankka.NewService(Ledger{})

func main() {}
