package ankka

import (
	"context"
	"go/ast"
	goparser "go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// FR-005, FR-013: a refusal names where it is. Every problem the SDK reports is made by
// declarations.problem(where, ...), so the source check below holds that no call passes an empty
// where, and the table holds what each kind's where is: its id, its route, its tool, its step or its
// handler, so a developer reading the refusal knows which declaration to open.
func TestEveryRefusalIsMadeNamingWhereItIs(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	calls := 0
	for _, name := range files {
		if strings.HasSuffix(name, "_test.go") {
			continue
		}
		src, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		f, err := goparser.ParseFile(fset, name, src, 0)
		if err != nil {
			t.Fatal(err)
		}
		ast.Inspect(f, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			sel, ok := call.Fun.(*ast.SelectorExpr)
			if !ok || sel.Sel.Name != "problem" || len(call.Args) < 2 {
				return true
			}
			calls++
			if lit, ok := call.Args[0].(*ast.BasicLit); ok && (lit.Value == `""` || lit.Value == "``") {
				// The one problem of no declaration names the declaration's place in the list.
				format, _ := call.Args[1].(*ast.BasicLit)
				if format == nil || !strings.Contains(format.Value, "declaration %d") {
					t.Errorf("%s: a refusal is made naming nowhere", fset.Position(call.Pos()))
				}
			}
			return true
		})
	}
	if calls < 100 {
		t.Fatalf("found %d refusals; the check is not reading what it should", calls)
	}
}

func TestEachKindsRefusalNamesItsDeclaration(t *testing.T) {
	ok := func(ctx context.Context, req *Request) (string, error) { return "", nil }
	twice := func(context.Context, tState, tItem) (Effect[tState, tEvent, int], error) {
		return Effect[tState, tEvent, int]{}, nil
	}
	for name, c := range map[string]struct {
		decl  Declaration
		where string
	}{
		"an event sourced entity":  {EventSourced[tState, tEvent]("bad-cart", nil, nil, nil), "bad-cart"},
		"a handler":                {EventSourced[tState, tEvent]("dup-cart", tEvents, func(string) tState { return tState{} }, tApply, Command("same", twice), Command("same", twice)), "dup-cart"},
		"a key value entity":       {KeyValue[tState]("bad-kv", nil), "bad-kv"},
		"a timed action":           {TimedAction("bad-ta"), "bad-ta"},
		"a view":                   {View[tCartRow]("bad-view"), "bad-view"},
		"a keyed view":             {KeyedView[tCartRow]("bad-keyed"), "bad-keyed"},
		"a workflow":               {Workflow[tState]("bad-flow", nil), "bad-flow"},
		"an agent":                 {Agent("bad-agent"), "bad-agent"},
		"a consumer":               {Consumer("bad-consumer", Topic("in"), func(context.Context, tNotice) (ConsumerEffect, error) { return ConsumerDone(), nil }), "bad-consumer"},
		"an endpoint":              {NewEndpoint("/bad", nil, Get("/", ok)), "endpoint /bad"},
		"a route":                  {NewEndpoint("/twice", AllowAll(), Get("/x", ok), Get("/x", ok)), "endpoint /twice GET"},
		"an MCP endpoint":          {NewMcpEndpoint("/mcp-bad", nil), "MCP endpoint /mcp-bad"},
		"an entity with no id":     {KeyValue[tState]("", nil), "key value entity of ankka.tState"},
		"a workflow with no id":    {Workflow[tState]("", nil), "workflow of ankka.tState"},
		"a declaration that isn't": {nil, ""},
	} {
		problems := NewService(c.decl).Problems()
		if len(problems) == 0 {
			t.Errorf("%s: no problem reported", name)
			continue
		}
		for _, p := range problems {
			if c.decl == nil {
				if !strings.Contains(p.String(), "declaration 1 is nil") {
					t.Errorf("%s: %q does not name its place", name, p.String())
				}
				continue
			}
			if !strings.HasPrefix(p.Where, c.where) || !strings.HasPrefix(p.String(), c.where+" ") && !strings.HasPrefix(p.String(), c.where+".") && !strings.HasPrefix(p.String(), c.where+":") {
				t.Errorf("%s: %q is not named %q", name, p.String(), c.where)
			}
		}
	}
}
