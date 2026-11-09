package samples

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// docs:start topic-view

// StockEvent is a message another system publishes to the stock-events topic.
type StockEvent struct {
	ProductID string `json:"productId"`
	Delta     int    `json:"delta"`
}

// StockRow is a product's level, as the view keeps it.
type StockRow struct {
	ProductID string `json:"productId"`
	Level     int    `json:"level"`
}

type stockEffect = ankka.ViewEffect[StockRow]

// StockLevels reads the topic as its source: each message is decoded as a StockEvent and keyed
// by its subject, the product.
var StockLevels = ankka.View[StockRow]("stock-levels",
	ankka.OnChange(ankka.Topic("stock-events"), func(ctx context.Context, e StockEvent, row *StockRow) (stockEffect, error) {
		c, _ := ankka.ChangeFrom(ctx)
		current := StockRow{ProductID: c.Subject}
		if row != nil {
			current = *row
		}
		current.Level += e.Delta
		return ankka.UpdateRow(current), nil
	}),
	ankka.RowCodec(ankka.CodecFor[StockRow](ankka.Manifest("stock-row"))),
)

// docs:end topic-view
