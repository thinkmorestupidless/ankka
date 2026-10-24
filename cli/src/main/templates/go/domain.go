// The stub domain: an item with a name and a count. Replace it with yours: this file, the entity
// that keeps it (item_entity.go), the endpoint that exposes it (api.go) and the view that lists it
// (item_rows.go) are the whole shape of an ankka service, and they are all in this project.
//
// A struct's JSON tags are the stored format, so keep them once data exists.
package main

import ankka "github.com/thinkmorestupidless/ankka-go"

// Item is an item's state.
type Item struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// Event is what has happened to an  Events are the durable record; state is derived from them.
// It is a sum type from the first event, so a second one later does not change how the first is
// stored: each case is written under its discriminator.
type Event interface{ itemEvent() }

// ItemAdded is a count of an item added.
type ItemAdded struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// ItemRemoved is a count of an item taken away.
type ItemRemoved struct {
	Count int `json:"count"`
}

func (ItemAdded) itemEvent()   {}
func (ItemRemoved) itemEvent() {}

// Events are the cases of Event and the discriminators the journal holds them under.
var Events = ankka.Cases[Event](
	ankka.Case[Event, ItemAdded]("ItemAdded"),
	ankka.Case[Event, ItemRemoved]("ItemRemoved"),
)

// AddItem is the request body of add-
type AddItem struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// RemoveItem is the request body of remove-
type RemoveItem struct {
	Count int `json:"count"`
}
