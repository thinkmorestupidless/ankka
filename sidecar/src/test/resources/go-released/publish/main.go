// Publishes the SDK's module at a version into a directory a GOPROXY=file:// reads, so the
// example's build depends on a released ankka-go, as a developer's does, and records its version.
package main

import (
	"archive/zip"
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

const modulePath = "github.com/thinkmorestupidless/ankka-go"

func main() {
	dir, version, proxy := os.Args[1], os.Args[2], os.Args[3]
	at := filepath.Join(proxy, modulePath, "@v")
	must(os.MkdirAll(at, 0o755))
	mod, err := os.ReadFile(filepath.Join(dir, "go.mod"))
	must(err)
	must(os.WriteFile(filepath.Join(at, "list"), []byte(version+"\n"), 0o644))
	info := `{"Version":"` + version + `","Time":"2026-10-01T00:00:00Z"}`
	must(os.WriteFile(filepath.Join(at, version+".info"), []byte(info), 0o644))
	must(os.WriteFile(filepath.Join(at, version+".mod"), mod, 0o644))
	out, err := os.Create(filepath.Join(at, version+".zip"))
	must(err)
	z := zip.NewWriter(out)
	must(filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		rel, err := filepath.Rel(dir, p)
		if err != nil {
			return err
		}
		w, err := z.Create(modulePath + "@" + version + "/" + filepath.ToSlash(rel))
		if err != nil {
			return err
		}
		f, err := os.Open(p)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = io.Copy(w, f)
		return err
	}))
	must(z.Close())
	must(out.Close())
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
