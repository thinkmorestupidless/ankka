use ankka::prelude::*;

#[derive(Serialize, Deserialize)]
struct Photo {
    name: String,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type")]
enum Photographed {
    Kept { name: String },
}

struct Album;

impl Album {
    // An entity's context has no buckets: `storage()` answers an `Option` that is `None` here, so
    // a handler cannot reach a bucket without saying what it does when there is none.
    fn keep(_: &Photo, bytes: String, ctx: &Context) -> Effect<Photographed, Done> {
        let storage: ankka::Storage = ctx.storage();
        storage.default().put("front.jpg", bytes.as_bytes(), "image/jpeg").unwrap();
        effects::persist(Photographed::Kept {
            name: "front.jpg".into(),
        })
        .then_reply_value(Done)
    }
}

impl EventSourcedEntity for Album {
    type State = Photo;
    type Event = Photographed;
    const COMPONENT_ID: &'static str = "album";

    fn empty_state(_: &str) -> Photo {
        Photo {
            name: String::new(),
        }
    }

    fn apply(state: Photo, _: &Photographed) -> Photo {
        state
    }

    fn handlers() -> Handlers<Album> {
        Handlers::new().command("keep", Album::keep)
    }
}

fn main() {}
