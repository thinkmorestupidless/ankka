package samples

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// CheckoutRecord is a checkout log's value: when the cart was checked out, and whether the
// customer was told.
type CheckoutRecord struct {
	CartID   string `json:"cartId"`
	At       int64  `json:"at"`
	Notified bool   `json:"notified"`
}

type checkoutLogEffect[R any] = ankka.KeyValueEffect[CheckoutRecord, R]

// CheckoutLog keeps the latest checkout of each cart: a key value entity replaces its whole value.
var CheckoutLog = ankka.KeyValue[CheckoutRecord]("checkout-log",
	func(cartID string) CheckoutRecord { return CheckoutRecord{CartID: cartID} },
	ankka.Update("record",
		func(_ context.Context, r CheckoutRecord, at int64) (checkoutLogEffect[ankka.Done], error) {
			if at <= 0 {
				return checkoutLogEffect[ankka.Done]{}, ankka.Refuse(ankka.BadRequest, "a time is needed")
			}
			r.At, r.Notified = at, true
			return ankka.UpdateState[CheckoutRecord, ankka.Done](r).ThenReplyValue(ankka.Done{}), nil
		}),
	ankka.Query("get",
		func(_ context.Context, r CheckoutRecord, _ ankka.Unit) (ankka.ReadOnlyEffect[CheckoutRecord], error) {
			return ankka.Reply(r), nil
		}),
	ankka.StateCodec(ankka.CodecFor[CheckoutRecord](ankka.Manifest("checkout-record"))),
)

// docs:start register-and-call

// CheckoutLogService registers the entity like any other component.
func CheckoutLogService() *ankka.Service { return ankka.NewService(CheckoutLog, CheckoutLogEndpoint) }

// CheckoutLogEndpoint reads a record through the client.
var CheckoutLogEndpoint = ankka.NewEndpoint("/checkout-log", ankka.AllowAll(),
	ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (CheckoutRecord, error) {
		return ankka.Invoke[CheckoutRecord](ctx, "checkout-log", req.Param("cartId"), "get", ankka.Unit{})
	}),
)

// docs:end register-and-call
