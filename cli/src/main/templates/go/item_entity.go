package main

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ItemEntityID is the entity's component id.
const ItemEntityID = "item"

type effect = ankka.Effect[Item, Event, ankka.Done]

// ItemEntity is an event sourced entity: one instance per item id, holding the item's state as the fold
// of its events. A handler returns an effect, a description of what should happen, and never
// performs I/O itself; that is what lets item_test.go drive it with no sidecar.
//
// The first argument of Command and Query is the wire name, the versioning boundary. Rename the Go
// function freely; change the wire name and in-flight callers break.
var ItemEntity = ankka.EventSourced[Item, Event](ItemEntityID, Events, empty, apply,
	ankka.Command("add-item", addItem),
	ankka.Command("remove-item", removeItem),
	// A query's handler returns a read-only effect: one that persists does not compile.
	ankka.Query("get-item", func(_ context.Context, i Item, _ ankka.Unit) (ankka.ReadOnlyEffect[Item], error) {
		return ankka.Reply(i), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Item](ankka.Manifest("item"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("item-event"))),
)

func empty(id string) Item { return Item{ID: id} }

func apply(i Item, e Event) Item {
	switch e := e.(type) {
	case ItemAdded:
		i.Name = e.Name
		i.Count += e.Count
	case ItemRemoved:
		i.Count -= e.Count
	}
	return i
}

func done(Item) ankka.Done { return ankka.Done{} }

// addItem is a command: validated, then persisted, then answered. A refusal is an error.
func addItem(_ context.Context, _ Item, req AddItem) (effect, error) {
	if req.Count <= 0 {
		return effect{}, ankka.Refusef(ankka.BadRequest, "count must be greater than zero, was %d", req.Count)
	}
	return ankka.Persist[Item, Event, ankka.Done](ItemAdded(req)).ThenReply(done), nil
}

func removeItem(_ context.Context, i Item, req RemoveItem) (effect, error) {
	if req.Count <= 0 {
		return effect{}, ankka.Refusef(ankka.BadRequest, "count must be greater than zero, was %d", req.Count)
	}
	if req.Count > i.Count {
		return effect{}, ankka.Refusef(ankka.Conflict, "only %d to remove", i.Count)
	}
	return ankka.Persist[Item, Event, ankka.Done](ItemRemoved(req)).ThenReply(done), nil
}
