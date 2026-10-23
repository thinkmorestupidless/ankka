// The process: `go run .`.
//
// Start serves the sidecar protocol on port 9010 on loopback, where the sidecar finds it. Locally the
// sidecar and Postgres come from `docker compose up -d`; in an ankka deployment the platform runs the
// sidecar beside this process and provides the database.
package main

import (
	"context"
	"fmt"
	"os"
)

func main() {
	if err := service().Start(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
