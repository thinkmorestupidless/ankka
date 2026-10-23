package ankka

import (
	"fmt"
	"reflect"
	"sync"
)

// Done is the reply of a handler that answers nothing but that it finished: manifest "done", zero bytes.
type Done struct{}

// Unit is the input of a handler that takes nothing: manifest "unit", zero bytes.
type Unit struct{}

// SumType is the registration of an interface's cases: which struct types a value of the interface
// may hold, and the discriminator each is written under ("type":"ItemAdded"). Built by Cases, it is
// referenced by the declaration that stores values of the interface.
type SumType[I any] struct {
	iface reflect.Type
}

// CaseOf is one case of a sum type: a struct type and its discriminator. Built by Case.
type CaseOf[I any] struct {
	discriminator string
	typ           reflect.Type
}

// Case is the struct type T as a case of the interface I, written with "type": discriminator. T, or a
// pointer to T, must implement I; a value read back is a T (or a *T when only the pointer implements I).
func Case[I, T any](discriminator string) CaseOf[I] {
	return CaseOf[I]{discriminator: discriminator, typ: reflect.TypeFor[T]()}
}

// Cases registers the cases of the interface I. Problems with the registration (a case that does not
// implement I, a discriminator used twice, a type registered twice) are reported when a service or a
// test kit uses I, naming the interface and the case.
func Cases[I any](cases ...CaseOf[I]) *SumType[I] {
	iface := reflect.TypeFor[I]()
	reg := &sumRegistration{iface: iface, byName: map[string]sumCase{}, byType: map[reflect.Type]string{}}
	if iface.Kind() != reflect.Interface {
		reg.problems = append(reg.problems, fmt.Sprintf("ankka.Cases[%s]: %s is not an interface type", iface, iface))
	}
	for _, c := range cases {
		where := fmt.Sprintf("ankka.Cases[%s]: case %s", iface, c.typ)
		if c.discriminator == "" {
			reg.problems = append(reg.problems, where+" has an empty discriminator")
			continue
		}
		if c.typ.Kind() != reflect.Struct {
			reg.problems = append(reg.problems, where+" is not a struct type")
			continue
		}
		pointer := false
		if iface.Kind() == reflect.Interface && !c.typ.Implements(iface) {
			if reflect.PointerTo(c.typ).Implements(iface) {
				pointer = true
			} else {
				reg.problems = append(reg.problems, fmt.Sprintf("%s does not implement %s", where, iface))
				continue
			}
		}
		if _, dup := reg.byName[c.discriminator]; dup {
			reg.problems = append(reg.problems, fmt.Sprintf("ankka.Cases[%s]: the discriminator %q is registered twice", iface, c.discriminator))
			continue
		}
		if prev, dup := reg.byType[c.typ]; dup {
			reg.problems = append(reg.problems, fmt.Sprintf("%s is registered twice, as %q and %q", where, prev, c.discriminator))
			continue
		}
		reg.byName[c.discriminator] = sumCase{discriminator: c.discriminator, typ: c.typ, pointer: pointer}
		reg.byType[c.typ] = c.discriminator
		reg.order = append(reg.order, c.discriminator)
	}
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if _, dup := registry.sums[iface]; dup {
		reg.problems = append(reg.problems, fmt.Sprintf("ankka.Cases[%s] is called twice; register every case of an interface once", iface))
		registry.sums[iface].problems = append(registry.sums[iface].problems, reg.problems...)
	} else {
		registry.sums[iface] = reg
	}
	registry.plans = map[reflect.Type]*plan{} // a plan built before this registration may have refused the interface
	return &SumType[I]{iface: iface}
}

// Type is the interface the cases belong to.
func (s *SumType[I]) Type() reflect.Type { return s.iface }

// Enum is the registration of a named string type's values, written as {"type":"Ready"} as the
// Scala codecs write a fieldless enum; a bare string is accepted on read. Built by Enumeration.
type Enum[E ~string] struct {
	typ reflect.Type
}

// Enumeration registers the values of the string type E.
func Enumeration[E ~string](values ...E) *Enum[E] {
	typ := reflect.TypeFor[E]()
	reg := &enumRegistration{typ: typ}
	seen := map[string]bool{}
	for _, v := range values {
		s := string(v)
		if s == "" {
			reg.problems = append(reg.problems, fmt.Sprintf("ankka.Enumeration[%s]: an empty value", typ))
			continue
		}
		if seen[s] {
			reg.problems = append(reg.problems, fmt.Sprintf("ankka.Enumeration[%s]: the value %q is registered twice", typ, s))
			continue
		}
		seen[s] = true
		reg.values = append(reg.values, s)
	}
	if len(values) == 0 {
		reg.problems = append(reg.problems, fmt.Sprintf("ankka.Enumeration[%s]: no values", typ))
	}
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if prev, dup := registry.enums[typ]; dup {
		prev.problems = append(prev.problems, fmt.Sprintf("ankka.Enumeration[%s] is called twice; register an enumeration's values once", typ))
	} else {
		registry.enums[typ] = reg
	}
	registry.plans = map[reflect.Type]*plan{}
	return &Enum[E]{typ: typ}
}

// Values are the registered values, in the order given.
func (e *Enum[E]) Values() []E {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	var out []E
	if reg := registry.enums[e.typ]; reg != nil {
		for _, v := range reg.values {
			out = append(out, E(v))
		}
	}
	return out
}

func isEnumeration(t reflect.Type) bool {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	return registry.enums[t] != nil
}

type sumCase struct {
	discriminator string
	typ           reflect.Type // the struct type
	pointer       bool         // only *typ implements the interface
}

type sumRegistration struct {
	iface    reflect.Type
	byName   map[string]sumCase
	byType   map[reflect.Type]string
	order    []string
	problems []string
}

type enumRegistration struct {
	typ      reflect.Type
	values   []string
	problems []string
}

// The process-wide registry of sum types, enumerations and the plans built from them.
var registry = struct {
	mu    sync.Mutex
	sums  map[reflect.Type]*sumRegistration
	enums map[reflect.Type]*enumRegistration
	plans map[reflect.Type]*plan
}{
	sums:  map[reflect.Type]*sumRegistration{},
	enums: map[reflect.Type]*enumRegistration{},
	plans: map[reflect.Type]*plan{},
}
