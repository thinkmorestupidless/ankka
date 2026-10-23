package ankka

import (
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// vet type-checks one file of testdata/compilefail, which its build tag keeps out of the package.
func vet(t *testing.T, name string) (string, error) {
	t.Helper()
	gobin, err := exec.LookPath("go")
	if err != nil {
		t.Skip("go is not on PATH")
	}
	out, err := exec.Command(gobin, "vet", filepath.Join("testdata", "compilefail", name)).CombinedOutput()
	return string(out), err
}

func TestWhatMustNotCompileDoesNot(t *testing.T) {
	cases := []struct{ file, says string }{
		{"query_persists.go", "as ankka.ReadOnlyEffect"},
		{"endpoint_without_acl.go", "as *ankka.ACL value"},
	}
	for _, c := range cases {
		t.Run(c.file, func(t *testing.T) {
			out, err := vet(t, c.file)
			if err == nil {
				t.Fatalf("%s compiled", c.file)
			}
			if !strings.Contains(out, c.says) {
				t.Fatalf("%s failed without naming %s:\n%s", c.file, c.says, out)
			}
		})
	}
}

func TestTheTwinCompiles(t *testing.T) {
	if out, err := vet(t, "compiles.go"); err != nil {
		t.Fatalf("the twin does not compile: %v\n%s", err, out)
	}
}
