package ankka

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"
)

// Every fixture under protocol/fixtures (the top level and autonomous/), decoded and re-encoded
// through the default codec of a Go type declared here as the Scala EncodingShapes declare it. A
// fixture with no declared type fails, never skips, and the last test asserts every file was named.

type fxLineItem struct {
	ProductID string `json:"productId"`
	Name      string `json:"name"`
	Quantity  int    `json:"quantity"`
}

type fxShoppingCart struct {
	Items      []fxLineItem `json:"items"`
	CheckedOut bool         `json:"checkedOut"`
}

type fxCartEvent interface{ fxCartEvent() }
type fxItemAdded struct {
	Item fxLineItem `json:"item"`
}
type fxItemRemoved struct {
	ProductID string `json:"productId"`
}
type fxCheckedOut struct{}

func (fxItemAdded) fxCartEvent()   {}
func (fxItemRemoved) fxCartEvent() {}
func (fxCheckedOut) fxCartEvent()  {}

type fxStep struct {
	Name  string `json:"name"`
	Order int    `json:"order"`
}

type fxPlan struct {
	Title  string            `json:"title"`
	Steps  []fxStep          `json:"steps"`
	Labels map[string]string `json:"labels"`
}

type fxStatus string

type fxService struct {
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"createdAt"`
	Owner     *string   `json:"owner"`
	Note      *string   `json:"note"`
	Status    fxStatus  `json:"status"`
}

type fxServiceEvent interface{ fxServiceEvent() }
type fxApplied struct {
	Generation int64      `json:"generation"`
	At         *time.Time `json:"at"`
}

func (fxApplied) fxServiceEvent() {}

type fxNumbers struct {
	Big        int64   `json:"big"`
	Half       float64 `json:"half"`
	TenBillion float64 `json:"tenBillion"`
	Tenth      float64 `json:"tenth"`
	Negative   int     `json:"negative"`
}

type fxTree struct {
	Label    string   `json:"label"`
	Children []fxTree `json:"children"`
}

// The autonomous agent's platform records.
type fxUsage struct {
	InputTokens  int64 `json:"inputTokens"`
	OutputTokens int64 `json:"outputTokens"`
}

type fxTaskEvent interface{ fxTaskEvent() }
type fxCreated struct {
	ID           string            `json:"id"`
	TypeName     string            `json:"typeName"`
	Instructions string            `json:"instructions"`
	Attachments  []json.RawMessage `json:"attachments"`
	Dependencies []string          `json:"dependencies"`
	At           int64             `json:"at"`
}
type fxCompleted struct {
	Result     string  `json:"result"`
	Iterations int     `json:"iterations"`
	Usage      fxUsage `json:"usage"`
	At         int64   `json:"at"`
}

func (fxCreated) fxTaskEvent()   {}
func (fxCompleted) fxTaskEvent() {}

type fxInstanceEvent interface{ fxInstanceEvent() }
type fxIterationCompleted struct {
	Iteration int     `json:"iteration"`
	Usage     fxUsage `json:"usage"`
	At        int64   `json:"at"`
}

func (fxIterationCompleted) fxInstanceEvent() {}

type fxNotification interface{ fxNotification() }
type fxApprovalRequested struct {
	ComponentID string `json:"componentId"`
	InstanceID  string `json:"instanceId"`
	TaskID      string `json:"taskId"`
	ApprovalID  string `json:"approvalId"`
	Tool        string `json:"tool"`
	Arguments   string `json:"arguments"`
	ExpiresAt   int64  `json:"expiresAt"`
	At          int64  `json:"at"`
}
type fxTaskResultRejected struct {
	ComponentID string `json:"componentId"`
	InstanceID  string `json:"instanceId"`
	TaskID      string `json:"taskId"`
	Reason      string `json:"reason"`
	Iteration   int    `json:"iteration"`
	At          int64  `json:"at"`
}

func (fxApprovalRequested) fxNotification()  {}
func (fxTaskResultRejected) fxNotification() {}

type fxCurrentTask struct {
	TaskID    string `json:"taskId"`
	Iteration int    `json:"iteration"`
	Budget    int    `json:"budget"`
}

type fxAwaiting struct {
	ID          string          `json:"id"`
	CallID      string          `json:"callId"`
	Tool        string          `json:"tool"`
	Arguments   json.RawMessage `json:"arguments"`
	RequestedAt int64           `json:"requestedAt"`
	ExpiresAt   int64           `json:"expiresAt"`
}

type fxAgentState struct {
	ComponentID string         `json:"componentId"`
	InstanceID  string         `json:"instanceId"`
	Phase       string         `json:"phase"`
	Suspended   bool           `json:"suspended"`
	Terminated  bool           `json:"terminated"`
	CurrentTask *fxCurrentTask `json:"currentTask"`
	Queued      []string       `json:"queued"`
	Usage       fxUsage        `json:"usage"`
	TaskUsage   fxUsage        `json:"taskUsage"`
	Awaiting    *[]fxAwaiting  `json:"awaiting"`
}

var (
	_ = Cases[fxCartEvent](
		Case[fxCartEvent, fxItemAdded]("ItemAdded"),
		Case[fxCartEvent, fxItemRemoved]("ItemRemoved"),
		Case[fxCartEvent, fxCheckedOut]("CheckedOut"),
	)
	_ = Cases[fxServiceEvent](Case[fxServiceEvent, fxApplied]("Applied"))
	_ = Enumeration[fxStatus]("Ready", "Failed")
	_ = Cases[fxTaskEvent](Case[fxTaskEvent, fxCreated]("Created"), Case[fxTaskEvent, fxCompleted]("Completed"))
	_ = Cases[fxInstanceEvent](Case[fxInstanceEvent, fxIterationCompleted]("IterationCompleted"))
	_ = Cases[fxNotification](
		Case[fxNotification, fxApprovalRequested]("ApprovalRequested"),
		Case[fxNotification, fxTaskResultRejected]("TaskResultRejected"),
	)
)

type fixture struct {
	Manifest    string          `json:"manifest"`
	ContentType string          `json:"content_type"`
	BytesBase64 string          `json:"bytes_base64"`
	Value       json.RawMessage `json:"value"`
}

// A check decodes a fixture with one codec and, unless decodeOnly says why not, encodes it back.
type check struct {
	run        func(t *testing.T, f fixture, data []byte)
	decodeOnly string
}

// jsonCheck: the decoded bytes equal the fixture's value decoded by the same codec, and encode back
// to the same bytes.
func jsonCheck[T any](manifest string, decodeOnly string) check {
	return check{decodeOnly: decodeOnly, run: func(t *testing.T, f fixture, data []byte) {
		c := CodecFor[T](Manifest(manifest))
		checkCodec(t, c, f)
		got, err := c.Decode(data)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		want, err := c.Decode(f.Value)
		if err != nil {
			t.Fatalf("decode of the fixture's value: %v", err)
		}
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("decoded %#v, the fixture's value is %#v", got, want)
		}
		if decodeOnly == "" {
			encoded, err := c.Encode(got)
			if err != nil {
				t.Fatalf("encode: %v", err)
			}
			if string(encoded) != string(data) {
				t.Fatalf("re-encoding changed the bytes:\n got %s\nwant %s", encoded, data)
			}
		}
	}}
}

// valueCheck: a text or binary fixture decodes to want and encodes back to the same bytes.
func valueCheck[T any](want T) check {
	return check{run: func(t *testing.T, f fixture, data []byte) {
		c := CodecFor[T]()
		checkCodec(t, c, f)
		got, err := c.Decode(data)
		if err != nil {
			t.Fatalf("decode: %v", err)
		}
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("decoded %#v, want %#v", got, want)
		}
		encoded, err := c.Encode(got)
		if err != nil {
			t.Fatalf("encode: %v", err)
		}
		if string(encoded) != string(data) {
			t.Fatalf("re-encoding changed the bytes: got %q, want %q", encoded, data)
		}
	}}
}

func checkCodec[T any](t *testing.T, c Codec[T], f fixture) {
	t.Helper()
	if problems := CodecProblems(c); len(problems) > 0 {
		t.Fatalf("the codec has problems: %v", problems)
	}
	if c.Manifest() != f.Manifest || c.ContentType() != f.ContentType {
		t.Fatalf("the codec is %s (%s), the fixture %s (%s)", c.Manifest(), c.ContentType(), f.Manifest, f.ContentType)
	}
}

func ptr[T any](v T) *T { return &v }

var checks = map[string]check{
	"binary-bytes.json":                                       valueCheck([]byte{1, 2, 3}),
	"binary-done.json":                                        valueCheck(Done{}),
	"binary-option-none.json":                                 valueCheck[*int](nil),
	"binary-option-some.json":                                 valueCheck(ptr(42)),
	"binary-unit.json":                                        valueCheck(Unit{}),
	"text-boolean.json":                                       valueCheck(true),
	"text-double.json":                                        valueCheck(1.5),
	"text-duration-millis.json":                               valueCheck(1500 * time.Millisecond),
	"text-int.json":                                           valueCheck(42),
	"text-long.json":                                          valueCheck(int64(9007199254740993)),
	"text-string.json":                                        valueCheck("hello, world"),
	"enum-as-field.json":                                      jsonCheck[fxStatus]("status", ""),
	"numbers.json":                                            jsonCheck[fxNumbers]("numbers", ""),
	"record-empty-collection.json":                            jsonCheck[fxShoppingCart]("shopping-cart", ""),
	"record-with-collection.json":                             jsonCheck[fxShoppingCart]("shopping-cart", ""),
	"record-instants-and-options.json":                        jsonCheck[fxService]("service", ""),
	"record-nested-and-map.json":                              jsonCheck[fxPlan]("plan", ""),
	"recursive.json":                                          jsonCheck[fxTree]("tree", ""),
	"sum-type-fieldless.json":                                 jsonCheck[fxCartEvent]("shopping-cart-event", ""),
	"sum-type-string-field.json":                              jsonCheck[fxCartEvent]("shopping-cart-event", ""),
	"sum-type-with-fields.json":                               jsonCheck[fxCartEvent]("shopping-cart-event", ""),
	"sum-type-optional-absent.json":                           jsonCheck[fxServiceEvent]("service-event", ""),
	"sum-type-with-instant.json":                              jsonCheck[fxServiceEvent]("service-event", ""),
	"autonomous/task-created.json":                            jsonCheck[fxTaskEvent]("task-event", ""),
	"autonomous/task-completed.json":                          jsonCheck[fxTaskEvent]("task-event", ""),
	"autonomous/agent-state.json":                             jsonCheck[fxAgentState]("agent-state", "a platform record the Scala codec writes without an empty awaiting list; a process reads it and never writes it"),
	"autonomous/agent-state-awaiting.json":                    jsonCheck[fxAgentState]("agent-state", ""),
	"autonomous/agent-instance-iteration-completed.json":      jsonCheck[fxInstanceEvent]("agent-instance-event", ""),
	"autonomous/agent-notification-approval-requested.json":   jsonCheck[fxNotification]("agent-notification", ""),
	"autonomous/agent-notification-task-result-rejected.json": jsonCheck[fxNotification]("agent-notification", ""),
}

// fixtureFiles are the codec fixtures: the top level of protocol/fixtures and autonomous/. The other
// directories hold rules and documents (contracts, graph deltas, storage and secret names, topic
// settings) that their own tests read.
func fixtureFiles(t *testing.T) []string {
	t.Helper()
	var out []string
	for _, dir := range []string{"protocol/fixtures", "protocol/fixtures/autonomous"} {
		entries, err := os.ReadDir(dir)
		if err != nil {
			t.Fatal(err)
		}
		for _, e := range entries {
			if !e.IsDir() && strings.HasSuffix(e.Name(), ".json") {
				out = append(out, strings.TrimPrefix(filepath.Join(dir, e.Name()), "protocol/fixtures/"))
			}
		}
	}
	sort.Strings(out)
	return out
}

func TestEveryFixtureRoundTrips(t *testing.T) {
	files := fixtureFiles(t)
	if len(files) < 30 {
		t.Fatalf("only %d fixtures found", len(files))
	}
	for _, name := range files {
		t.Run(name, func(t *testing.T) {
			c, ok := checks[name]
			if !ok {
				t.Fatalf("no Go type is declared for %s; add one to checks", name)
			}
			text, err := os.ReadFile(filepath.Join("protocol/fixtures", name))
			if err != nil {
				t.Fatal(err)
			}
			var f fixture
			if err := json.Unmarshal(text, &f); err != nil {
				t.Fatal(err)
			}
			data, err := base64.StdEncoding.DecodeString(f.BytesBase64)
			if err != nil {
				t.Fatal(err)
			}
			c.run(t, f, data)
		})
	}
	for name := range checks {
		if !contains(files, name) {
			t.Errorf("checks names %s, which is not a fixture", name)
		}
	}
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}
