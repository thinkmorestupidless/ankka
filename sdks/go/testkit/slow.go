package testkit

import (
	"os"
	"testing"
)

// Slow marks a test that starts containers: it is skipped unless ANKKA_SLOW=1, and always under
// go test -short.
func Slow(t testing.TB) {
	t.Helper()
	if testing.Short() {
		t.Skip("a slow test: skipped under -short")
	}
	if os.Getenv("ANKKA_SLOW") != "1" {
		t.Skip("a slow test: set ANKKA_SLOW=1 to run it")
	}
}
