// Package server is the process's side of the sidecar protocol: the grpc-go server, one servicer per
// kind, and the conversations they hold. It knows protocol messages and nothing of the developer's
// types; the root package adapts each declaration to the host interfaces here, and the test kits drive
// the same hosts without a network.
package server

import (
	"context"
	"fmt"
	"log/slog"
	"runtime/debug"
	"sync"

	"google.golang.org/grpc"
)

// New is a grpc-go server with no options beyond what the sidecar needs: it speaks plain HTTP/2 on
// loopback (V1), so no credentials and no keepalive policy are set.
func New(opts ...grpc.ServerOption) *grpc.Server {
	return grpc.NewServer(opts...)
}

// Options are what every servicer is given: where to log, and how to scope a conversation's or a
// request's context (the client, the configuration) before a handler sees it.
type Options struct {
	Log   *slog.Logger
	Scope func(context.Context) context.Context
}

func (o Options) scope(ctx context.Context) context.Context {
	if o.Scope == nil {
		return ctx
	}
	return o.Scope(ctx)
}

func (o Options) log() *slog.Logger {
	if o.Log == nil {
		return slog.Default()
	}
	return o.Log
}

// PanicError is a handler's panic, recovered into its request's failure.
type PanicError struct {
	Value any
	Stack []byte
}

func (e *PanicError) Error() string { return fmt.Sprintf("the handler panicked: %v", e.Value) }

// Guard runs f, turning a panic into a *PanicError so the request fails and the process lives.
// Every call into a developer's handler goes through it.
func Guard(f func() error) (err error) {
	defer func() {
		if p := recover(); p != nil {
			err = &PanicError{Value: p, Stack: debug.Stack()}
		}
	}()
	return f()
}

// GuardValue is Guard for a function with a result.
func GuardValue[T any](f func() (T, error)) (v T, err error) {
	err = Guard(func() error {
		var e error
		v, e = f()
		return e
	})
	return v, err
}

// Sender is a stream's one sender: grpc-go allows one goroutine at a time in Send, and a workflow's
// command and step answer from two.
type Sender[T any] struct {
	mu     sync.Mutex
	stream interface{ Send(T) error }
}

// NewSender wraps stream.
func NewSender[T any](stream interface{ Send(T) error }) *Sender[T] {
	return &Sender[T]{stream: stream}
}

// Send sends m, one at a time.
func (s *Sender[T]) Send(m T) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stream.Send(m)
}
