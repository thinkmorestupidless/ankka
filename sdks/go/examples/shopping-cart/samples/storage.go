package samples

import (
	"context"
	"io"
	"strings"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// docs:start listing

// PhotoRecorded is what a listing records about a photo: its name in the bucket, never its bytes.
type PhotoRecorded struct {
	Name string `json:"name"`
}

// ListingState is the names of a listing's photos.
type ListingState struct {
	Photos []string `json:"photos"`
}

type listingEffect = ankka.Effect[ListingState, PhotoRecorded, ankka.Done]

// Listing records the photos kept for it.
var Listing = ankka.EventSourced[ListingState, PhotoRecorded]("listing", nil,
	func(string) ListingState { return ListingState{} },
	func(s ListingState, e PhotoRecorded) ListingState {
		s.Photos = append(append([]string{}, s.Photos...), e.Name)
		return s
	},
	ankka.Command("record-photo", func(_ context.Context, _ ListingState, name string) (listingEffect, error) {
		return ankka.Persist[ListingState, PhotoRecorded, ankka.Done](PhotoRecorded{Name: name}).
			ThenReplyValue(ankka.Done{}), nil
	}),
	ankka.Query("photos", func(_ context.Context, s ListingState, _ ankka.Unit) (ankka.ReadOnlyEffect[ListingState], error) {
		return ankka.Reply(s), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ListingState](ankka.Manifest("listing"))),
	ankka.EventCodec(ankka.CodecFor[PhotoRecorded](ankka.Manifest("photo-recorded"))),
)

// docs:end listing

// ObjectInfo is what the describe route answers.
type ObjectInfo struct {
	Name        string `json:"name"`
	ContentType string `json:"contentType"`
	Length      int64  `json:"length"`
}

// docs:start photos-endpoint

// bucketOf is the bucket the request names with ?bucket=, or the unnamed bucket.
func bucketOf(ctx context.Context, req *ankka.Request) (ankka.Bucket, error) {
	store := ankka.StorageFrom(ctx)
	if store == nil {
		return nil, ankka.Refuse(ankka.Unavailable, "this service has no object storage")
	}
	if req.Query.Has("bucket") {
		return store.Bucket(req.Query.Get("bucket"))
	}
	return store.Default()
}

// PhotosEndpoint is an endpoint over the service's buckets: keep, read, list, describe, delete,
// sign and address. A Go route reads its body whole, so an upload of any size goes through a
// signed URL instead.
var PhotosEndpoint = ankka.NewEndpoint("/storage", ankka.AllowAll(),
	// Keeps the body under the name, as the content type it was sent as.
	ankka.Put("/objects/{name}", func(ctx context.Context, req *ankka.Request, body string) (ankka.Done, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.Done{}, err
		}
		contentType := req.ContentType
		if contentType == "" {
			contentType = "application/octet-stream"
		}
		return ankka.Done{}, bucket.Put(ctx, req.Param("name"), strings.NewReader(body), int64(len(body)), contentType)
	}),
	// The object's bytes as they were kept, under their own content type; 404 for none.
	ankka.Get("/objects/{name}", func(ctx context.Context, req *ankka.Request) (ankka.RawResponse, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.RawResponse{}, err
		}
		obj, err := bucket.Get(ctx, req.Param("name"))
		if err != nil || obj == nil {
			return ankka.RawResponse{}, notFoundUnless(err, "no object '"+req.Param("name")+"'")
		}
		data, err := io.ReadAll(obj.Body)
		return ankka.RawResponse{Status: 200, ContentType: obj.ContentType, Body: data}, err
	}),
	// What is kept under the name, without its bytes.
	ankka.Get("/objects/{name}/info", func(ctx context.Context, req *ankka.Request) (ObjectInfo, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ObjectInfo{}, err
		}
		info, err := bucket.Stat(ctx, req.Param("name"))
		if err != nil || info == nil {
			return ObjectInfo{}, notFoundUnless(err, "no object '"+req.Param("name")+"'")
		}
		return ObjectInfo{Name: req.Param("name"), ContentType: info.ContentType, Length: info.Length}, nil
	}),
	// Every name under the prefix, as the store pages through them.
	ankka.Get("/objects", func(ctx context.Context, req *ankka.Request) ([]string, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return nil, err
		}
		names := []string{}
		for entry, err := range bucket.List(ctx, req.Query.Get("prefix")) {
			if err != nil {
				return nil, err
			}
			names = append(names, entry.Name)
		}
		return names, nil
	}),
	ankka.Delete("/objects/{name}", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.Done{}, err
		}
		return ankka.Done{}, bucket.Delete(ctx, req.Param("name"))
	}),
	// A URL a browser may use for a quarter of an hour to read the object, computed here: no
	// request reaches the store, and the bytes never pass through the service.
	ankka.Get("/objects/{name}/url", func(ctx context.Context, req *ankka.Request) (string, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return "", err
		}
		return bucket.SignedURL(req.Param("name"), ankka.AccessRead, 0)
	}),
	// Keeps the bytes and gives the listing the name: the entity records what the bucket holds.
	ankka.Post("/listings/{listing}/photos/{name}", func(ctx context.Context, req *ankka.Request,
		body string) (ankka.Done, error) {
		bucket, err := bucketOf(ctx, req)
		if err != nil {
			return ankka.Done{}, err
		}
		name := req.Param("listing") + "/" + req.Param("name")
		if err := bucket.Put(ctx, name, strings.NewReader(body), int64(len(body)), "image/png"); err != nil {
			return ankka.Done{}, err
		}
		return ankka.Invoke[ankka.Done](ctx, "listing", req.Param("listing"), "record-photo", name)
	}),
)

// docs:end photos-endpoint

// notFoundUnless is err, or a 404 with the message when there is none.
func notFoundUnless(err error, message string) error {
	if err != nil {
		return err
	}
	return &ankka.HttpProblem{Status: 404, Message: message}
}
