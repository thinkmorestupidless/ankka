package conformance

import (
	"context"
	"slices"
	"strings"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ── checkout-rows: a view of a workflow ──

// docs:start workflow-view

// CheckoutRow is a checkout's row: its state's status, and where the workflow stood when it
// recorded it.
type CheckoutRow struct {
	ID       string  `json:"id"`
	Status   string  `json:"status"`
	Standing string  `json:"standing"`
	Step     *string `json:"step"`
	Failure  *string `json:"failure"`
}

func optional(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// CheckoutRows is a checkout's row, by its id.
var CheckoutRows = ankka.View[CheckoutRow]("checkout-rows",
	ankka.OnChange(ankka.WorkflowSource(CheckoutFlow), func(ctx context.Context, s Checkout, _ *CheckoutRow) (ankka.ViewEffect[CheckoutRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		row := CheckoutRow{ID: c.Subject, Status: s.Status, Standing: "Unknown"}
		// A change from a workflow carries its standing: where it stood once the effect that
		// recorded s was applied.
		if st := c.Standing; st != nil {
			row.Standing, row.Step, row.Failure = st.Status, optional(st.Step), optional(st.Failure)
		}
		return ankka.UpdateRow(row), nil
	}),
	ankka.RowCodec(ankka.CodecFor[CheckoutRow](ankka.Manifest("checkout-row"))),
	ankka.Queries(ankka.Declared("by-standing", "SELECT payload FROM "+ankka.TableOf("checkout-rows")+" WHERE payload::jsonb->>'standing' = :standing")),
)

// docs:end workflow-view

// ── topic-rows: a view over a topic ──

// Fanned is the message the topic cases publish.
type Fanned struct {
	N int `json:"n"`
}

var fannedCodec = ankka.CodecFor[Fanned](ankka.Manifest("fanned"))

// docs:start topic-sources

// TopicRows is the latest message about each subject. It declares no start, so it reads from the
// earliest.
var TopicRows = ankka.View[Fanned]("topic-rows",
	ankka.OnChange(ankka.Topic("conformance-topic"), func(_ context.Context, m Fanned, _ *Fanned) (ankka.ViewEffect[Fanned], error) {
		return ankka.UpdateRow(m), nil
	}),
	ankka.SourceVersion(2),
	ankka.RowCodec(fannedCodec),
)

// docs:end topic-sources

// ── tree-rows: a tree, walked by a declared recursive query ──

// TreeRow is one node: its key and its parent.
type TreeRow struct {
	Key   string  `json:"key"`
	Under *string `json:"under"`
}

// docs:start declared-query

// TreeRows is one row per node, and every row under a row, to any depth, by key: the same
// statement in every language.
var TreeRows = ankka.View[TreeRow]("tree-rows",
	ankka.OnChange(ankka.Changes(TreeNode), func(ctx context.Context, e TreePlaced, _ *TreeRow) (ankka.ViewEffect[TreeRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		return ankka.UpdateRow(TreeRow{Key: c.Subject, Under: e.Under}), nil
	}),
	ankka.RowCodec(ankka.CodecFor[TreeRow](ankka.Manifest("tree-row"))),
	ankka.Queries(
		ankka.Declared("under", `WITH RECURSIVE below AS (
  SELECT row_key, payload FROM `+ankka.TableOf("tree-rows")+` WHERE payload::jsonb->>'under' = :row
  UNION
  SELECT n.row_key, n.payload FROM `+ankka.TableOf("tree-rows")+` n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
)
SELECT payload FROM below ORDER BY row_key`),
		// docs:start watched-query
		ankka.WatchedQuery("all-rows", "SELECT row_key, payload FROM "+ankka.TableOf("tree-rows")+" ORDER BY row_key"),
		// docs:end watched-query
	),
)

// docs:end declared-query

// ── joined-rows: a keyed view of two sources ──

// JoinedRow is a row of the keyed view: its key, the right it holds, and who noted it.
type JoinedRow struct {
	Key     string   `json:"key"`
	Holding string   `json:"holding"`
	Notes   []string `json:"notes"`
}

// docs:start keyed-view

// JoinedRows: the left names a row key|holding and writes it from what it held, noting itself;
// the right finds every row holding it by asking the view's own query, and notes itself on each.
var JoinedRows = ankka.KeyedView[JoinedRow]("joined-rows",
	ankka.On(ankka.Changes(JoinedLeft), func(_ context.Context, e Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		key, holding, _ := strings.Cut(e.Text, "|")
		held, _, err := rows.Get(key)
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		notes := append(slices.Clone(held.Notes), "left")
		return ankka.UpdateRowAt(key, JoinedRow{Key: key, Holding: holding, Notes: notes}), nil
	}),
	ankka.On(ankka.Changes(JoinedRight), func(ctx context.Context, _ Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		theirs, err := rows.Ask("of-right", map[string]string{"holding": c.Subject})
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		var each []ankka.KeyedViewEffect[JoinedRow]
		for _, r := range theirs {
			r.Notes = append(slices.Clone(r.Notes), "right")
			each = append(each, ankka.UpdateRowAt(r.Key, r))
		}
		return ankka.AllRows(each...), nil
	}),
	ankka.RowCodec(ankka.CodecFor[JoinedRow](ankka.Manifest("joined-row"))),
	ankka.Queries(ankka.Declared("of-right", "SELECT payload FROM "+ankka.TableOf("joined-rows")+" WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key")),
)

// docs:end keyed-view
