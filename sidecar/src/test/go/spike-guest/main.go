// The Go spike guest. Exports the `ankka1_` functions of protocol/WASM-ABI.md, over the protocol's
// own messages and the envelopes in wasm.proto, hand-encoded with protowire (the wire-level package
// under google.golang.org/protobuf, which reflects on nothing), and the cart the Rust guest carries.
// Like the Rust guest it has a `panic` handler that panics and a `config` handler answering what the
// `ankka1::config` import returns, so the host's tests run against a module in two languages.
//
// Bytes cross the boundary as (pointer, length) in linear memory, and a function that returns
// bytes packs pointer and length into one u64, pointer high. TinyGo's collector does not move
// objects, so a slice handed to the host stays where it is for as long as `live` references it.
package main

import (
	"encoding/json"
	"unsafe"

	"google.golang.org/protobuf/encoding/protowire"
)

func main() {}

// ---- the domain: the cart every sample uses, in the stored spelling --------------------------

type Item struct {
	ProductID string `json:"productId"`
	Name      string `json:"name"`
	Quantity  int    `json:"quantity"`
}

type Cart struct {
	Items []Item `json:"items"`
}

// Event is the one-case sum type. Go has no tagged unions, so the discriminator the encoding
// wants is written and read by hand: this is the per-language codec cost the treatment names.
type Event struct {
	Type string `json:"type"`
	Item Item   `json:"item"`
}

func fold(cart Cart, event Event) Cart {
	switch event.Type {
	case "ItemAdded":
		cart.Items = append(cart.Items, event.Item)
	}
	return cart
}

func decodeCart(state []byte) Cart {
	cart := Cart{Items: []Item{}}
	if len(state) > 0 {
		if err := json.Unmarshal(state, &cart); err != nil {
			panic(err)
		}
	}
	if cart.Items == nil {
		cart.Items = []Item{}
	}
	return cart
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

// ---- memory ----------------------------------------------------------------------------------

var live = map[uintptr][]byte{}

//go:wasmexport ankka1_alloc
func alloc(n uint32) uint32 {
	b := make([]byte, n, max(n, 1))
	p := uintptr(unsafe.Pointer(unsafe.SliceData(b)))
	live[p] = b
	return uint32(p)
}

//go:wasmexport ankka1_free
func free(p uint32, n uint32) {
	delete(live, uintptr(p))
}

func take(p uint32, n uint32) []byte {
	b := live[uintptr(p)]
	delete(live, uintptr(p))
	return b[:n]
}

func give(b []byte) uint64 {
	if len(b) == 0 {
		b = make([]byte, 0, 1)
	}
	p := uintptr(unsafe.Pointer(unsafe.SliceData(b)))
	live[p] = b
	return uint64(p)<<32 | uint64(len(b))
}

// ---- the protocol's messages, by hand over protowire -----------------------------------------

type payload struct {
	contentType string
	manifest    string
	data        []byte
}

func (p payload) encode() []byte {
	var b []byte
	b = protowire.AppendTag(b, 1, protowire.BytesType)
	b = protowire.AppendString(b, p.contentType)
	b = protowire.AppendTag(b, 2, protowire.BytesType)
	b = protowire.AppendString(b, p.manifest)
	b = protowire.AppendTag(b, 3, protowire.BytesType)
	b = protowire.AppendBytes(b, p.data)
	return b
}

func jsonPayload(manifest string, v any) payload {
	return payload{contentType: "application/json", manifest: manifest, data: mustJSON(v)}
}

// fields walks a message once and hands each field to `f`; a varint arrives as its value,
// anything length-delimited as its bytes.
func fields(b []byte, f func(num protowire.Number, varint uint64, bytes []byte)) {
	for len(b) > 0 {
		num, typ, n := protowire.ConsumeTag(b)
		if n < 0 {
			panic(protowire.ParseError(n))
		}
		b = b[n:]
		switch typ {
		case protowire.VarintType:
			v, n := protowire.ConsumeVarint(b)
			f(num, v, nil)
			b = b[n:]
		case protowire.BytesType:
			v, n := protowire.ConsumeBytes(b)
			f(num, 0, v)
			b = b[n:]
		default:
			n := protowire.ConsumeFieldValue(num, typ, b)
			b = b[n:]
		}
	}
}

func decodePayload(b []byte) payload {
	var p payload
	fields(b, func(num protowire.Number, _ uint64, bytes []byte) {
		switch num {
		case 1:
			p.contentType = string(bytes)
		case 2:
			p.manifest = string(bytes)
		case 3:
			p.data = bytes
		}
	})
	return p
}

type command struct {
	id      int64
	name    string
	payload payload
}

func decodeCommand(b []byte) command {
	var c command
	fields(b, func(num protowire.Number, varint uint64, bytes []byte) {
		switch num {
		case 1:
			c.id = int64(varint)
		case 2:
			c.name = string(bytes)
		case 3:
			c.payload = decodePayload(bytes)
		}
	})
	return c
}

func message(num protowire.Number, sub []byte) []byte {
	b := protowire.AppendTag(nil, num, protowire.BytesType)
	return protowire.AppendBytes(b, sub)
}

// ---- the exports -----------------------------------------------------------------------------

//go:wasmexport ankka1_discover
func discover(p uint32, n uint32) uint64 {
	var protocolVersion string
	fields(take(p, n), func(num protowire.Number, _ uint64, bytes []byte) {
		if num == 1 {
			protocolVersion = string(bytes)
		}
	})
	handler := func(name string, readOnly bool) []byte {
		var b []byte
		b = protowire.AppendTag(b, 1, protowire.BytesType)
		b = protowire.AppendString(b, name)
		b = protowire.AppendTag(b, 2, protowire.VarintType)
		b = protowire.AppendVarint(b, boolVarint(readOnly))
		return b
	}
	var component []byte
	component = protowire.AppendTag(component, 1, protowire.VarintType)
	component = protowire.AppendVarint(component, 0) // EVENT_SOURCED_ENTITY
	component = protowire.AppendTag(component, 2, protowire.BytesType)
	component = protowire.AppendString(component, "cart")
	component = append(component, message(3, handler("add-item", false))...)
	component = append(component, message(3, handler("get-cart", true))...)
	component = append(component, message(3, handler("panic", true))...)
	component = append(component, message(3, handler("config", true))...)
	var detail []byte
	detail = protowire.AppendTag(detail, 1, protowire.VarintType)
	detail = protowire.AppendVarint(detail, 100)
	component = append(component, message(10, detail)...)

	var sdk []byte
	sdk = protowire.AppendTag(sdk, 1, protowire.BytesType)
	sdk = protowire.AppendString(sdk, "ankka-spike-guest-go")
	sdk = protowire.AppendTag(sdk, 2, protowire.BytesType)
	sdk = protowire.AppendString(sdk, "0.0.0")

	var spec []byte
	spec = protowire.AppendTag(spec, 1, protowire.BytesType)
	spec = protowire.AppendString(spec, protocolVersion)
	spec = append(spec, message(2, sdk)...)
	spec = append(spec, message(3, component)...)

	// WasmSpec: the spec, no stateful components, ABI version 1.
	wasm := message(1, spec)
	wasm = protowire.AppendTag(wasm, 3, protowire.BytesType)
	wasm = protowire.AppendString(wasm, "1")
	return give(wasm)
}

func boolVarint(b bool) uint64 {
	if b {
		return 1
	}
	return 0
}

//go:wasmexport ankka1_handle
func handle(p uint32, n uint32) uint64 {
	var state []byte
	var cmd command
	// HandleRequest: state is field 4, an event sourced command field 10.
	fields(take(p, n), func(num protowire.Number, _ uint64, bytes []byte) {
		switch num {
		case 4:
			state = decodePayload(bytes).data
		case 10:
			cmd = decodeCommand(bytes)
		}
	})
	cart := decodeCart(state)

	var events [][]byte
	var outcome []byte
	switch cmd.name {
	case "add-item":
		var item Item
		if err := json.Unmarshal(cmd.payload.data, &item); err != nil {
			panic(err)
		}
		event := Event{Type: "ItemAdded", Item: item}
		cart = fold(cart, event)
		events = append(events, jsonPayload("Event", event).encode())
		done := payload{contentType: "application/octet-stream", manifest: "done"}
		outcome = message(1, message(1, done.encode()))
	case "get-cart":
		outcome = message(1, message(1, jsonPayload("Cart", cart).encode()))
	case "panic":
		panic("the panic handler panicked, as asked")
	case "config":
		name := string(cmd.payload.data)
		if value, ok := config(name); ok {
			text := payload{contentType: "text/plain", manifest: "string", data: []byte(value)}
			outcome = message(1, message(1, text.encode()))
		} else {
			outcome = message(3, errorMessage("no variable "+name, 4)) // NOT_FOUND
		}
	default:
		outcome = message(3, errorMessage("no handler "+cmd.name, 4)) // NOT_FOUND
	}

	var reply []byte
	reply = protowire.AppendTag(reply, 1, protowire.VarintType)
	reply = protowire.AppendVarint(reply, uint64(cmd.id))
	for _, e := range events {
		reply = append(reply, message(2, e)...)
	}
	reply = append(reply, message(4, outcome)...)

	// HandleReply: the event sourced reply is field 10, the state after it field 2.
	out := message(10, reply)
	out = append(out, message(2, jsonPayload("Cart", cart).encode())...)
	return give(out)
}

func errorMessage(text string, code uint64) []byte {
	var e []byte
	e = protowire.AppendTag(e, 1, protowire.BytesType)
	e = protowire.AppendString(e, text)
	e = protowire.AppendTag(e, 2, protowire.VarintType)
	return protowire.AppendVarint(e, code)
}

//go:wasmexport ankka1_fold
func foldExport(p uint32, n uint32) uint64 {
	// FoldRequest: state is field 3, the event field 4.
	var state, eventBytes []byte
	fields(take(p, n), func(num protowire.Number, _ uint64, bytes []byte) {
		switch num {
		case 3:
			state = decodePayload(bytes).data
		case 4:
			eventBytes = decodePayload(bytes).data
		}
	})
	var event Event
	if err := json.Unmarshal(eventBytes, &event); err != nil {
		panic(err)
	}
	// FoldReply: the state is field 1.
	return give(message(1, jsonPayload("Cart", fold(decodeCart(state), event)).encode()))
}

// ---- the other exports: fixed answers, so the host's dispatch of each is exercised ------------

//go:wasmexport ankka1_close
func closeExport(p uint32, n uint32) {
	take(p, n) // stateless: there is nothing kept to drop
}

//go:wasmexport ankka1_run_step
func runStep(p uint32, n uint32) uint64 {
	// StepRequest: state is field 3, run_step field 4, whose id is field 1.
	var state []byte
	var id uint64
	fields(take(p, n), func(num protowire.Number, _ uint64, bytes []byte) {
		switch num {
		case 3:
			state = bytes
		case 4:
			fields(bytes, func(num protowire.Number, varint uint64, _ []byte) {
				if num == 1 {
					id = varint
				}
			})
		}
	})
	// WorkflowOut.StepReply: command_id (1), next (3) = StepOutcome{end (3)}.
	var step []byte
	step = protowire.AppendTag(step, 1, protowire.VarintType)
	step = protowire.AppendVarint(step, id)
	step = append(step, message(3, message(3, nil))...)
	// StepReply: reply (1), the state handed over, unchanged (2).
	out := message(1, step)
	if state != nil {
		out = append(out, message(2, state)...)
	}
	return give(out)
}

func fixed(p uint32, n uint32, reply []byte) uint64 {
	take(p, n)
	return give(reply)
}

func text(num protowire.Number, value string) []byte {
	b := protowire.AppendTag(nil, num, protowire.BytesType)
	return protowire.AppendString(b, value)
}

//go:wasmexport ankka1_view
func view(p uint32, n uint32) uint64 { return fixed(p, n, message(3, nil)) } // ViewEffect.ignore

//go:wasmexport ankka1_consumer
func consumer(p uint32, n uint32) uint64 { return fixed(p, n, message(2, nil)) } // ConsumerEffect.done

//go:wasmexport ankka1_timed_action
func timedAction(p uint32, n uint32) uint64 { return fixed(p, n, message(1, nil)) } // TimedActionEffect.done

//go:wasmexport ankka1_plan
func plan(p uint32, n uint32) uint64 { return fixed(p, n, message(1, text(3, "hello"))) } // PlanReply.plan{user}

//go:wasmexport ankka1_invoke_tool
func invokeTool(p uint32, n uint32) uint64 { return fixed(p, n, text(1, "tool ran")) } // ToolResult.ok

//go:wasmexport ankka1_check_guardrail
func checkGuardrail(p uint32, n uint32) uint64 { return fixed(p, n, message(1, nil)) } // GuardrailResult.pass

//go:wasmexport ankka1_http
func http(p uint32, n uint32) uint64 {
	// HttpReply.response: status (1), content_type (2), body (3).
	var r []byte
	r = protowire.AppendTag(r, 1, protowire.VarintType)
	r = protowire.AppendVarint(r, 200)
	r = append(r, text(2, "text/plain")...)
	r = append(r, text(3, "hello")...)
	return fixed(p, n, message(1, r))
}

// ---- a host call, for the blocking measurement -----------------------------------------------

//go:wasmimport ankka1 invoke
func invoke(p uint32, n uint32) uint64

//go:wasmimport ankka1 config
func configImport(p uint32, n uint32) uint64

// config asks the host for one of the descriptor's variables: ConfigRequest's name is field 1,
// ConfigReply's optional value field 1, absent when the host withholds or lacks it.
func config(name string) (string, bool) {
	request := protowire.AppendTag(nil, 1, protowire.BytesType)
	request = protowire.AppendString(request, name)
	packed := configImport(uint32(uintptr(unsafe.Pointer(unsafe.SliceData(request)))), uint32(len(request)))
	if packed == 0 {
		return "", false
	}
	var value string
	found := false
	fields(take(uint32(packed>>32), uint32(packed&0xffffffff)), func(num protowire.Number, _ uint64, bytes []byte) {
		if num == 1 {
			value, found = string(bytes), true
		}
	})
	return value, found
}

//go:wasmexport ankka1_call_out
func callOut(p uint32, n uint32) uint64 {
	request := take(p, n)
	packed := invoke(uint32(uintptr(unsafe.Pointer(unsafe.SliceData(request)))), uint32(len(request)))
	return give(take(uint32(packed>>32), uint32(packed&0xffffffff)))
}
