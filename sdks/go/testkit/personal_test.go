package testkit

import (
	"strings"
	"testing"
)

// The query searches the JVM kit's forms of each value, quotes doubled, and the failure names each
// place as the JVM kit's does.
func TestThePersonalValueQueryNamesEachFormAndPlace(t *testing.T) {
	sql := personalValueQuery([]string{"ada@example.com", "o'neil"})
	for _, want := range []string{"'YWRhQGV4YW1wbGUuY29t'", "'616461406578616d706c652e636f6d'", "'o''neil'", "'base64url'"} {
		if !strings.Contains(sql, want) {
			t.Errorf("the query has no %s:\n%s", want, sql)
		}
	}
	got := personalValueFailure([]string{"ada@example.com"}, "0 base64 public.event_journal.event_payload\n")
	want := "a personal value can be read back from what the service kept:\n  'ada@example.com' as base64 in public.event_journal.event_payload"
	if got != want {
		t.Errorf("the failure is %q, not %q", got, want)
	}
	if got := personalValueFailure([]string{"ada@example.com"}, ""); got != "" {
		t.Errorf("nothing found failed with %q", got)
	}
}
