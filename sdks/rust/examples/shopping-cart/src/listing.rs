//! The object storage page's examples: an entity that records a photo's name, never its bytes, and
//! an endpoint over the service's buckets. Not in the module's service; `build` assembles them for
//! the tests.

use ankka::prelude::*;

// docs:start listing-entity
/// What a listing records about a photo: its name in the bucket, never its bytes.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct PhotoRecorded {
    pub name: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
pub struct ListingState {
    pub photos: Vec<String>,
}

pub struct Listing;

impl Listing {
    fn record_photo(_: &ListingState, name: String, _: &Context) -> Effect<PhotoRecorded, Done> {
        effects::persist(PhotoRecorded { name }).then_reply_value(Done)
    }

    fn photos(listing: &ListingState, _: (), _: &Context) -> ReadOnlyEffect<ListingState> {
        effects::reply(listing.clone())
    }
}

impl EventSourcedEntity for Listing {
    type State = ListingState;
    type Event = PhotoRecorded;
    const COMPONENT_ID: &'static str = "listing";
    const STATE_MANIFEST: Option<&'static str> = Some("listing");
    const EVENT_MANIFEST: Option<&'static str> = Some("photo-recorded");

    fn empty_state(_: &str) -> ListingState {
        ListingState::default()
    }

    fn apply(mut listing: ListingState, event: &PhotoRecorded) -> ListingState {
        listing.photos.push(event.name.clone());
        listing
    }

    fn handlers() -> Handlers<Listing> {
        Handlers::new()
            .command("record-photo", Listing::record_photo)
            .query("photos", Listing::photos)
    }
}
// docs:end listing-entity

/// What a post keeps: the object's name, its text and its content type.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct KeepObject {
    pub name: String,
    pub text: String,
    #[serde(rename = "contentType")]
    pub content_type: String,
}

/// What `stat` answers.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Described {
    pub name: String,
    #[serde(rename = "contentType")]
    pub content_type: String,
    pub length: u64,
}

// docs:start photos-endpoint
/// An endpoint over the service's buckets: keep, read, list, describe, delete, and record a photo.
pub struct PhotosEndpoint;

impl PhotosEndpoint {
    /// The bucket `bucket` names in the query, or the unnamed one.
    fn bucket(request: &Request) -> Result<Bucket, HttpProblem> {
        let storage = request
            .context()
            .storage()
            .ok_or_else(|| HttpProblem::new(500, "an endpoint has buckets"))?;
        Ok(match request.query("bucket") {
            Some(name) => storage.bucket(name),
            None => storage.default(),
        })
    }

    fn name(request: &Request) -> Result<&str, HttpProblem> {
        request
            .query("name")
            .ok_or_else(|| HttpProblem::new(400, "the query needs 'name'"))
    }

    fn keep(request: &Request, keep: KeepObject) -> Result<Done, HttpProblem> {
        Self::bucket(request)?.put(&keep.name, keep.text.as_bytes(), &keep.content_type)?;
        Ok(Done)
    }

    /// The object as it was kept, under its own content type.
    fn read(request: &Request) -> Result<Response, HttpProblem> {
        let name = Self::name(request)?;
        let object = Self::bucket(request)?
            .get(name)?
            .ok_or_else(|| HttpProblem::new(404, format!("no object '{name}'")))?;
        Ok(Response::bytes(object.content_type, object.body))
    }

    fn stat(request: &Request) -> Result<Described, HttpProblem> {
        let name = Self::name(request)?;
        let info = Self::bucket(request)?
            .stat(name)?
            .ok_or_else(|| HttpProblem::new(404, format!("no object '{name}'")))?;
        Ok(Described {
            name: name.to_string(),
            content_type: info.content_type,
            length: info.length,
        })
    }

    fn list(request: &Request) -> Result<Vec<String>, HttpProblem> {
        let prefix = request.query("prefix").unwrap_or_default();
        let entries = Self::bucket(request)?.list(prefix)?;
        Ok(entries.into_iter().map(|e| e.name).collect())
    }

    fn remove(request: &Request) -> Result<Done, HttpProblem> {
        Self::bucket(request)?.delete(Self::name(request)?)?;
        Ok(Done)
    }

    /// Keeps the bytes, then gives the entity the name: the entity never sees the bytes.
    fn photo(request: &Request, keep: KeepObject) -> Result<Done, HttpProblem> {
        Self::bucket(request)?.put(&keep.name, keep.text.as_bytes(), &keep.content_type)?;
        Ok(request
            .client()
            .invoke(Listing, request.path("id"), "record-photo", keep.name)?)
    }

    fn photos(request: &Request) -> Result<ListingState, HttpProblem> {
        Ok(request
            .client()
            .invoke(Listing, request.path("id"), "photos", ())?)
    }
}

impl Endpoint for PhotosEndpoint {
    const ENDPOINT_ID: &'static str = "PhotosEndpoint";
    const PREFIX: &'static str = "/storage";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<PhotosEndpoint> {
        Routes::new()
            .post("/objects", PhotosEndpoint::keep)
            .get("/objects", PhotosEndpoint::read)
            .get("/stat", PhotosEndpoint::stat)
            .get("/list", PhotosEndpoint::list)
            .delete("/objects", PhotosEndpoint::remove)
            .post("/photos/{id}", PhotosEndpoint::photo)
            .get("/photos/{id}", PhotosEndpoint::photos)
    }
}
// docs:end photos-endpoint

/// The listing and the endpoint over the buckets, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(Listing)
        .endpoint(PhotosEndpoint)
}
