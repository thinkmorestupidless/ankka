package ankka

import (
	"context"
	"log/slog"
	"net"
	"testing"
	"time"
)

// A service's store is the process's while it serves and no longer once it stops, so a handler a
// later test calls with a bare context finds no store, whichever tests ran before it (#228).
func TestAStoppedServiceLeavesNoProcessStore(t *testing.T) {
	if got := StorageFrom(context.Background()); got != NoStorage {
		t.Fatalf("before any service: %T, want NoStorage", got)
	}
	store := NewMemoryStorage()
	svc := NewService()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	served := make(chan error, 1)
	go func() {
		served <- svc.Serve(ctx, lis, StorageOf(store), Logger(slog.New(slog.DiscardHandler)), GracePeriod(time.Second))
	}()
	deadline := time.Now().Add(5 * time.Second)
	for StorageFrom(context.Background()) != Storage(store) {
		if time.Now().After(deadline) {
			t.Fatal("the serving service's store never became the process's")
		}
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	if err := <-served; err != nil {
		t.Fatal(err)
	}
	if got := StorageFrom(context.Background()); got != NoStorage {
		t.Fatalf("after the service stopped: %T, want NoStorage", got)
	}
}

// A service that stops after a second one started leaves the second one's store in place.
func TestRestoringAnOlderStoreLeavesANewerOne(t *testing.T) {
	first, second := NewMemoryStorage(), NewMemoryStorage()
	restoreFirst := useProcessStorage(first)
	restoreSecond := useProcessStorage(second)
	restoreFirst()
	if got := StorageFrom(context.Background()); got != Storage(second) {
		t.Fatalf("got %p, want the newer store %p", got, second)
	}
	restoreSecond()
	if got := StorageFrom(context.Background()); got != Storage(first) {
		t.Fatalf("got %T, want the store before the newer one", got)
	}
	processStoreMu.Lock()
	processStore, processStoreOwner = NoStorage, 0
	processStoreMu.Unlock()
}
