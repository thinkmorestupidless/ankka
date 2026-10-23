// Command conformance serves the Go reference service and runs the platform's conformance suite
// against it, exiting with sbt's status. It needs Docker (the suite starts Postgres) and sbt on PATH.
// ANKKA_CONFORMANCE_ONLY narrows the run to matching behaviours ('*es.*'); ANKKA_BENCHMARKS turns
// the suite's benchmarks on; ANKKA_CONFORMANCE_SECRETS=secret-manager runs the secret cases on the
// Secret Manager backend.
package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/conformance"
)

func main() { os.Exit(run()) }

func run() int {
	port := os.Getenv("ANKKA_PROCESS_PORT")
	if port == "" {
		port = "9010"
	}
	// The reference's buckets are in memory, as the suite's other targets' are; a caller's own
	// ANKKA_STORAGE_BACKEND wins.
	if os.Getenv("ANKKA_STORAGE_BACKEND") == "" {
		_ = os.Setenv("ANKKA_STORAGE_BACKEND", "memory")
	}
	repo, err := repository()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	service := conformance.Service()
	if problems := service.Problems(); len(problems) > 0 {
		fmt.Fprintln(os.Stderr, &ankka.ProblemsError{Problems: problems})
		return 1
	}
	lis, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", port))
	if err != nil {
		fmt.Fprintf(os.Stderr, "conformance: cannot listen on 127.0.0.1:%s: %v\n", port, err)
		return 1
	}
	ctx, stop := context.WithCancel(context.Background())
	served := make(chan error, 1)
	go func() {
		served <- service.Serve(ctx, lis)
	}()
	defer func() { stop(); <-served }()
	fmt.Fprintf(os.Stderr, "conformance: reference service listening on 127.0.0.1:%s\n", port)

	args := []string{"-Dankka.conformance.target=127.0.0.1:" + port}
	if os.Getenv("ANKKA_BENCHMARKS") != "" {
		args = append(args, "-Dankka.benchmarks=on")
	}
	if s := os.Getenv("ANKKA_CONFORMANCE_SECRETS"); s != "" {
		args = append(args, "-Dankka.conformance.secrets="+s)
	}
	test := "sidecar/testOnly *ConformanceSuite"
	if only := os.Getenv("ANKKA_CONFORMANCE_ONLY"); only != "" {
		test += " -- " + only
	}
	// The suite runs for minutes; each case is reported as it ends rather than all at the end.
	args = append(args, "-Dankka.cluster.tests=off", "-Dankka.template.tests=off", "set sidecar / Test / logBuffered := false", test)
	fmt.Fprintf(os.Stderr, "running: sbt %q in %s\n", args, repo)
	cmd := exec.Command("sbt", args...)
	cmd.Dir, cmd.Stdin, cmd.Stdout, cmd.Stderr = repo, os.Stdin, os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			return exit.ExitCode()
		}
		fmt.Fprintf(os.Stderr, "could not run sbt: %v\n", err)
		return 1
	}
	return 0
}

// repository is the checkout's root: six directories above this file's.
func repository() (string, error) {
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		return "", fmt.Errorf("conformance: cannot tell where this source file is")
	}
	return filepath.Abs(filepath.Join(filepath.Dir(file), "..", "..", "..", "..", "..", ".."))
}
