package samples

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestThePhotosAreKeptReadListedAndRecorded(t *testing.T) {
	if p := ankka.NewService(Listing, PhotosEndpoint).Problems(); len(p) > 0 {
		t.Fatal(p)
	}
	// A store in memory, handed to the handlers through the context: the unnamed bucket, signed.
	store := ankka.NewMemoryStorage(ankka.MemoryUnnamed(true), ankka.MemoryUnnamedExposure(ankka.ExposureSigned))
	ctx := ankka.WithStorage(context.Background(), store)
	kit := testkit.Endpoint(PhotosEndpoint, Listing)
	defer kit.Close()

	put := kit.Do(ctx, testkit.Request{Method: http.MethodPut, Path: "/storage/objects/a.txt", Body: "hello",
		Header: http.Header{"Content-Type": {"text/plain"}}})
	if put.Status != 204 {
		t.Fatalf("keeping answered %d %s", put.Status, put.Text())
	}
	if r := kit.Get(ctx, "/storage/objects/a.txt"); r.Text() != "hello" || !strings.HasPrefix(r.ContentType, "text/plain") {
		t.Fatalf("reading answered %d %q as %q", r.Status, r.Text(), r.ContentType)
	}
	if r := kit.Get(ctx, "/storage/objects/b.txt"); r.Status != 404 {
		t.Fatalf("reading nothing answered %d", r.Status)
	}
	var info ObjectInfo
	if r := kit.Get(ctx, "/storage/objects/a.txt/info"); json.Unmarshal(r.Body, &info) != nil || info.Length != 5 {
		t.Fatalf("describing answered %d %s", r.Status, r.Text())
	}
	if r := kit.Get(ctx, "/storage/objects/a.txt/url"); r.Status != 200 || !strings.Contains(r.Text(), "a.txt") {
		t.Fatalf("signing answered %d %s", r.Status, r.Text())
	}

	if r := kit.Post(ctx, "/storage/listings/l1/photos/front.png", "png"); r.Status != 204 {
		t.Fatalf("a photo answered %d %s", r.Status, r.Text())
	}
	if r := kit.Get(ctx, "/storage/objects?prefix=l1/"); r.Text() != `["l1/front.png"]` {
		t.Fatalf("listing answered %d %s", r.Status, r.Text())
	}
	listing := testkit.EventSourced(Listing, testkit.EntityID("l1"))
	if r := listing.Command(ctx, "record-photo", "l1/back.png"); len(r.Events()) != 1 {
		t.Fatalf("recording answered %v", r.Refusal())
	}
	if r := kit.Do(ctx, testkit.Request{Method: http.MethodDelete, Path: "/storage/objects/a.txt"}); r.Status != 204 {
		t.Fatalf("deleting answered %d %s", r.Status, r.Text())
	}
	if r := kit.Get(ctx, "/storage/objects/a.txt"); r.Status != 404 {
		t.Fatalf("a deleted object answered %d", r.Status)
	}
	if r := kit.Get(context.Background(), "/storage/objects"); r.Status != 503 && r.Status != 500 {
		t.Fatalf("no storage answered %d %s", r.Status, r.Text())
	}
}
