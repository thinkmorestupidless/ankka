//! The reference service: the cart, and what the platform's conformance suite drives beside it.
//! Every component, wire name and route is the Scala reference's (`ConformanceReference` in the
//! sidecar's tests) and the Python one's — except the streaming routes, the streaming agent handler
//! and an autonomous agent's notifications, which a module cannot have: it answers every call whole.
//!
//! Built with the `conformance` feature, the module is this service rather than the example's;
//! `conformance.sh` builds it and runs the suite against it in both guest shapes, which it reads
//! from `ANKKA_CONFORMANCE_SHAPE`.

use std::collections::BTreeMap;

use ankka::client::NewTask;
use ankka::codec::Auto;
use ankka::effects::{agent, consumer};
use ankka::personal::Personal;
use ankka::prelude::*;
use ankka::serde_json::Value;

use crate::cart_graph::CartGraph;
use crate::cart_rows::CartRows;
use crate::checkout_workflow::{Checkout, CheckoutWorkflow};
use crate::domain::ShoppingCartEvent;
use crate::endpoint::CartApi;
use crate::entity::ShoppingCart;
use crate::service_calls::{ServiceAsks, ServiceCallsEndpoint, ServiceRelay, ServiceSteps};

// ── conformance: an entity whose handlers are the protocol's edge cases ──

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Recorded {
    pub input: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Recordings {
    pub items: Vec<String>,
}

pub struct Conformance;

impl Conformance {
    fn record(_: &Recordings, input: String, _: &Context) -> Effect<Recorded, String> {
        effects::persist(Recorded { input }).then_reply_value("done".to_string())
    }

    fn record_many(_: &Recordings, n: i32, _: &Context) -> Effect<Recorded, String> {
        let events = (0..n).map(|i| Recorded {
            input: format!("many-{i}"),
        });
        effects::persist_all(events).then_reply_value("done".to_string())
    }

    fn refuse(_: &Recordings, _: (), _: &Context) -> Effect<Recorded, String> {
        effects::error(ErrorCode::Conflict, "refused on purpose").into()
    }

    fn no_reply(_: &Recordings, _: (), _: &Context) -> Effect<Recorded, String> {
        effects::persist(Recorded {
            input: "silent".into(),
        })
        .then_no_reply()
    }

    fn delete(_: &Recordings, _: (), _: &Context) -> Effect<Recorded, String> {
        effects::delete_entity().then_reply_value("done".to_string())
    }

    fn expire(_: &Recordings, millis: i64, _: &Context) -> Effect<Recorded, String> {
        effects::persist(Recorded {
            input: "expiring".into(),
        })
        .expire_after(Duration::of_millis(millis))
        .then_reply_value("done".to_string())
    }

    fn count(recordings: &Recordings, _: (), _: &Context) -> ReadOnlyEffect<i32> {
        effects::reply(recordings.items.len() as i32)
    }

    fn misbehave(_: &Recordings, _: (), _: &Context) -> Effect<Recorded, String> {
        panic!("boom")
    }
}

impl EventSourcedEntity for Conformance {
    type State = Recordings;
    type Event = Recorded;
    const COMPONENT_ID: &'static str = "conformance";
    const STATE_MANIFEST: Option<&'static str> = Some("conformance-state");
    const EVENT_MANIFEST: Option<&'static str> = Some("conformance-event");

    fn empty_state(_: &str) -> Recordings {
        Recordings::default()
    }

    fn apply(mut recordings: Recordings, event: &Recorded) -> Recordings {
        recordings.items.push(event.input.clone());
        recordings
    }

    fn handlers() -> Handlers<Conformance> {
        Handlers::new()
            .command("record", Conformance::record)
            .command("record-many", Conformance::record_many)
            .command("refuse", Conformance::refuse)
            .command("no-reply", Conformance::no_reply)
            .command("delete", Conformance::delete)
            .command("expire", Conformance::expire)
            .query("count", Conformance::count)
            .command("misbehave", Conformance::misbehave)
    }

    fn snapshot_every() -> u32 {
        3
    }
}

// ── profile: a key value entity ──

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ProfileState {
    pub name: String,
}

pub struct Profile;

impl Profile {
    fn set(_: &ProfileState, name: String, _: &Context) -> KeyValueEffect<ProfileState, String> {
        if name.is_empty() {
            return effects::error(ErrorCode::BadRequest, "a name is needed").into();
        }
        effects::update_state(ProfileState { name }).then_reply_value("done".to_string())
    }

    fn get(profile: &ProfileState, _: (), _: &Context) -> ReadOnlyEffect<String> {
        let name = if profile.name.is_empty() {
            "none".to_string()
        } else {
            profile.name.clone()
        };
        effects::reply(name)
    }

    fn delete(_: &ProfileState, _: (), _: &Context) -> KeyValueEffect<ProfileState, String> {
        effects::delete_state().then_reply_value("done".to_string())
    }
}

impl KeyValueEntity for Profile {
    type State = ProfileState;
    const COMPONENT_ID: &'static str = "profile";
    const STATE_MANIFEST: Option<&'static str> = Some("profile");

    fn empty_state(_: &str) -> ProfileState {
        ProfileState::default()
    }

    fn handlers() -> KeyValueHandlers<Profile> {
        KeyValueHandlers::new()
            .command("set", Profile::set)
            .query("get", Profile::get)
            .command("delete", Profile::delete)
    }
}

// ── checkout-recorder: a consumer that acts through the client ──

pub struct CheckoutRecorder;

impl Consumer for CheckoutRecorder {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "checkout-recorder";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> ConsumerEffect {
        let ShoppingCartEvent::CheckedOut = event else {
            return consumer::ignore();
        };
        let cart_id = ctx.metadata().subject().unwrap_or_default();
        let recorded: Result<String, CommandError> =
            ctx.client()
                .invoke(Conformance, cart_id, "record", "checkout".to_string());
        recorded.expect("the conformance entity records the checkout");
        consumer::done()
    }
}

// ── checkout-fanout: a consumer that publishes several messages for one change ──

// docs:start fanout
/// What `checkout-fanout` publishes: the n-th message of a change.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Fanned {
    pub n: i32,
}

/// One fanned message, encoded under the manifest every reference gives it.
fn fanned(n: i32) -> Outgoing {
    Outgoing::of(Auto::<Fanned>::named("fanned").to_payload(&Fanned { n }))
}

/// Three messages for a checkout — the second under a key of its own, the third with a header —
/// none for an item added, and a single one, the old way, for an item removed.
pub struct CheckoutFanout;

impl Consumer for CheckoutFanout {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "checkout-fanout";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn produces_to() -> Option<&'static str> {
        Some("conformance-fanout")
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> ConsumerEffect {
        match event {
            ShoppingCartEvent::ItemAdded { .. } => consumer::produce_all([]),
            ShoppingCartEvent::ItemRemoved { .. } => {
                let (payload, _, metadata) = fanned(0).into_parts();
                ConsumerEffect::Produce(payload, metadata)
            }
            ShoppingCartEvent::CheckedOut => consumer::produce_all([
                fanned(1),
                fanned(2).key(format!("second:{}", ctx.entity_id())),
                fanned(3).metadata(Metadata::new().set("x-n", "3")),
            ]),
            ShoppingCartEvent::Discarded => consumer::ignore(),
        }
    }
}
// docs:end fanout

// ── tree-node, tree-rows: a tree, walked by a declared recursive query ──

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Placed {
    pub under: Option<String>,
}

pub struct TreeNode;

impl TreeNode {
    /// `under` is the parent's id, or empty for a root.
    fn place(_: &Option<String>, under: String, _: &Context) -> Effect<Placed, String> {
        let under = Some(under).filter(|u| !u.is_empty());
        effects::persist(Placed { under }).then_reply_value("placed".to_string())
    }
}

impl EventSourcedEntity for TreeNode {
    type State = Option<String>;
    type Event = Placed;
    const COMPONENT_ID: &'static str = "tree-node";
    const STATE_MANIFEST: Option<&'static str> = Some("tree-node");
    const EVENT_MANIFEST: Option<&'static str> = Some("tree-event");

    fn empty_state(_: &str) -> Option<String> {
        None
    }

    fn apply(_: Option<String>, event: &Placed) -> Option<String> {
        event.under.clone()
    }

    fn handlers() -> Handlers<TreeNode> {
        Handlers::new().command("place", TreeNode::place)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TreeRow {
    pub key: String,
    pub under: Option<String>,
}

pub struct TreeRows;

impl View for TreeRows {
    type Row = TreeRow;
    type Event = Placed;
    const COMPONENT_ID: &'static str = "tree-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("tree-row");

    fn source() -> Source {
        Source::of(TreeNode)
    }

    fn on_event(_: Option<TreeRow>, event: Placed, ctx: &Context) -> ViewEffect<TreeRow> {
        ViewEffect::UpdateRow(TreeRow {
            key: ctx.metadata().subject().unwrap_or_default().to_string(),
            under: event.under,
        })
    }

    // docs:start declared-recursive-query
    /// Every row under the row `row`, to any depth, in key order.
    fn declared() -> Vec<DeclaredQuery> {
        let table = table_of(Self::COMPONENT_ID);
        vec![query(
            "under",
            format!(
                "WITH RECURSIVE below AS (\n  \
                 SELECT row_key, payload FROM {table} WHERE payload::jsonb->>'under' = :row\n  \
                 UNION\n  \
                 SELECT n.row_key, n.payload FROM {table} n JOIN below b ON n.payload::jsonb->>'under' = b.row_key\n\
                 )\n\
                 SELECT payload FROM below ORDER BY row_key"
            ),
        )]
    }
    // docs:end declared-recursive-query
}

/// Places nodes of a tree and asks what is under one.
pub struct TreeEndpoint;

impl TreeEndpoint {
    fn root(request: &Request, (): ()) -> Result<String, HttpProblem> {
        let node = request.path("nodeId");
        Ok(request
            .client()
            .invoke(TreeNode, node, "place", String::new())?)
    }

    fn under(request: &Request, (): ()) -> Result<String, HttpProblem> {
        let node = request.path("nodeId");
        let parent = request.path("parentId").to_string();
        Ok(request.client().invoke(TreeNode, node, "place", parent)?)
    }

    fn below(request: &Request) -> Result<Vec<String>, HttpProblem> {
        let node = request.path("nodeId");
        let rows: Vec<TreeRow> = request.client().ask(TreeRows, "under", &[("row", node)])?;
        Ok(rows.into_iter().map(|row| row.key).collect())
    }
}

impl Endpoint for TreeEndpoint {
    const ENDPOINT_ID: &'static str = "TreeEndpoint";
    const PREFIX: &'static str = "/tree";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<TreeEndpoint> {
        Routes::new()
            .post("/{nodeId}", TreeEndpoint::root)
            .post("/{nodeId}/under/{parentId}", TreeEndpoint::under)
            .get("/{nodeId}/below", TreeEndpoint::below)
    }
}

// ── member, member-rows: a personal field (protocol 1.15) ──
//
// The email is a personal field of the data subject `member/<id>`; the row the view keeps marks it
// for lookup, so a declared query finds a member by email without the table ever holding it.

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemberJoined {
    #[serde(rename = "memberId")]
    pub member_id: String,
    pub email: Personal<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct MemberState {
    pub email: Option<Personal<String>>,
}

pub struct Member;

impl Member {
    fn join(_: &MemberState, email: String, ctx: &Context) -> Effect<MemberJoined, String> {
        let id = ctx.entity_id().to_string();
        let email = match Personal::lookup(format!("member/{id}"), email) {
            Ok(email) => email,
            Err(e) => return effects::error(ErrorCode::BadRequest, e.to_string()).into(),
        };
        effects::persist(MemberJoined {
            member_id: id,
            email,
        })
        .then_reply_value("done".to_string())
    }

    fn email(state: &MemberState, _: (), _: &Context) -> ReadOnlyEffect<String> {
        effects::reply(match &state.email {
            None => "none".to_string(),
            Some(email) => email
                .as_ref()
                .cloned()
                .unwrap_or_else(|| "erased".to_string()),
        })
    }
}

impl EventSourcedEntity for Member {
    type State = MemberState;
    type Event = MemberJoined;
    const COMPONENT_ID: &'static str = "member";
    const STATE_MANIFEST: Option<&'static str> = Some("member-state");
    const EVENT_MANIFEST: Option<&'static str> = Some("member-event");

    fn empty_state(_: &str) -> MemberState {
        MemberState::default()
    }

    fn apply(_: MemberState, event: &MemberJoined) -> MemberState {
        MemberState {
            email: Some(event.email.clone()),
        }
    }

    fn handlers() -> Handlers<Member> {
        Handlers::new()
            .command("join", Member::join)
            .query("email", Member::email)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemberRow {
    #[serde(rename = "memberId")]
    pub member_id: String,
    pub email: Personal<String>,
}

pub struct MemberRows;

impl View for MemberRows {
    type Row = MemberRow;
    type Event = MemberJoined;
    const COMPONENT_ID: &'static str = "member-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("member-row");

    fn source() -> Source {
        Source::of(Member)
    }

    // The journal carries no token: the row marks the email for lookup where it is written.
    fn on_event(_: Option<MemberRow>, event: MemberJoined, _: &Context) -> ViewEffect<MemberRow> {
        ViewEffect::UpdateRow(MemberRow {
            member_id: event.member_id,
            email: event.email.for_lookup(),
        })
    }

    fn declared() -> Vec<DeclaredQuery> {
        let table = table_of(Self::COMPONENT_ID);
        vec![query(
            "by-email",
            format!(
                "SELECT payload FROM {table} WHERE payload::jsonb->'email'->>'lookup' = :email ORDER BY row_key"
            ),
        )]
    }
}

// ── joined-left, joined-right, joined-rows: a keyed view of two sources ──

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Noted {
    pub text: String,
}

fn record_noted(_: &i32, text: String, _: &Context) -> Effect<Noted, String> {
    effects::persist(Noted { text }).then_reply_value("recorded".to_string())
}

pub struct JoinedLeft;

impl EventSourcedEntity for JoinedLeft {
    type State = i32;
    type Event = Noted;
    const COMPONENT_ID: &'static str = "joined-left";
    const STATE_MANIFEST: Option<&'static str> = Some("joining");
    const EVENT_MANIFEST: Option<&'static str> = Some("noted");

    fn empty_state(_: &str) -> i32 {
        0
    }

    fn apply(state: i32, _: &Noted) -> i32 {
        state + 1
    }

    fn handlers() -> Handlers<JoinedLeft> {
        Handlers::new().command("record", record_noted)
    }
}

pub struct JoinedRight;

impl EventSourcedEntity for JoinedRight {
    type State = i32;
    type Event = Noted;
    const COMPONENT_ID: &'static str = "joined-right";
    const STATE_MANIFEST: Option<&'static str> = Some("joining");
    const EVENT_MANIFEST: Option<&'static str> = Some("noted");

    fn empty_state(_: &str) -> i32 {
        0
    }

    fn apply(state: i32, _: &Noted) -> i32 {
        state + 1
    }

    fn handlers() -> Handlers<JoinedRight> {
        Handlers::new().command("record", record_noted)
    }
}

/// A row the left writes under the key it names, holding a right entity's id.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JoinedRow {
    pub key: String,
    pub holding: String,
    pub notes: Vec<String>,
}

// docs:start keyed-view
pub struct JoinedRows;

impl JoinedRows {
    /// The left names a row `key|holding`, and writes it from what it held, noting itself.
    fn on_left(event: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let (key, holding) = event
            .text
            .split_once('|')
            .unwrap_or((event.text.as_str(), ""));
        let mut notes = ctx
            .rows()
            .get::<JoinedRow>(key)
            .map(|row| row.notes)
            .unwrap_or_default();
        notes.push("left".to_string());
        KeyedViewEffect::update_row(
            key,
            JoinedRow {
                key: key.to_string(),
                holding: holding.to_string(),
                notes,
            },
        )
    }

    /// The right finds every row holding it by asking the view's own query, and notes itself.
    fn on_right(_: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let subject = ctx.metadata().subject().unwrap_or_default();
        let theirs: Vec<JoinedRow> = ctx.rows().ask("of-right", &[("holding", subject)]);
        KeyedViewEffect::update_rows(theirs.into_iter().map(|mut row| {
            row.notes.push("right".to_string());
            (row.key.clone(), row)
        }))
    }
}

impl KeyedView for JoinedRows {
    type Row = JoinedRow;
    const COMPONENT_ID: &'static str = "joined-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("joined-row");

    fn sources() -> Sources<JoinedRows> {
        Sources::new()
            .on::<Noted>(Source::of(JoinedLeft), JoinedRows::on_left)
            .on::<Noted>(Source::of(JoinedRight), JoinedRows::on_right)
    }

    /// The rows holding one right entity, by key: the same statement in every language.
    fn declared() -> Vec<DeclaredQuery> {
        vec![query(
            "of-right",
            format!(
                "SELECT payload FROM {} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
                table_of(Self::COMPONENT_ID)
            ),
        )]
    }
}
// docs:end keyed-view

/// Records on either side of the keyed view, and reads its rows.
pub struct JoinedEndpoint;

impl JoinedEndpoint {
    fn left(request: &Request, (): ()) -> Result<String, HttpProblem> {
        let text = format!("{}|{}", request.path("key"), request.path("holding"));
        Ok(request
            .client()
            // The left entity is the row's own key: one left per row.
            .invoke(JoinedLeft, request.path("key"), "record", text)?)
    }

    fn right(request: &Request, (): ()) -> Result<String, HttpProblem> {
        Ok(request.client().invoke(
            JoinedRight,
            request.path("rightId"),
            "record",
            String::new(),
        )?)
    }

    fn row(request: &Request) -> Result<JoinedRow, HttpProblem> {
        let key = request.path("key").to_string();
        let rows: Vec<JoinedRow> = request.client().query(JoinedRows, "get", key.clone())?;
        rows.into_iter()
            .next()
            .ok_or_else(|| HttpProblem::new(404, format!("no row '{key}'")))
    }
}

impl Endpoint for JoinedEndpoint {
    const ENDPOINT_ID: &'static str = "JoinedEndpoint";
    const PREFIX: &'static str = "/joined";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<JoinedEndpoint> {
        Routes::new()
            .post("/left/{key}/{holding}", JoinedEndpoint::left)
            .post("/right/{rightId}", JoinedEndpoint::right)
            .get("/rows/{key}", JoinedEndpoint::row)
    }
}

// ── topic-rows and topic-relay: a view and a consumer over a topic ──

// docs:start topic-sources
/// The latest message about each subject. Declares no start, so it reads from the earliest.
pub struct TopicRows;

impl View for TopicRows {
    type Row = Fanned;
    type Event = Fanned;
    const COMPONENT_ID: &'static str = "topic-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("fanned");

    fn source() -> Source {
        Source::topic("conformance-topic")
    }

    fn version() -> Option<u32> {
        Some(2)
    }

    fn on_event(_: Option<Fanned>, message: Fanned, _: &Context) -> ViewEffect<Fanned> {
        ViewEffect::UpdateRow(message)
    }
}

/// Republishes what it reads, from the latest: none of what the topic held when it started.
pub struct TopicRelay;

impl Consumer for TopicRelay {
    type Message = Fanned;
    const COMPONENT_ID: &'static str = "topic-relay";

    fn source() -> Source {
        Source::topic("conformance-topic")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Latest)
    }

    fn produces_to() -> Option<&'static str> {
        Some("conformance-topic-relayed")
    }

    fn on_message(message: Fanned, _: &Context) -> ConsumerEffect {
        let (payload, _, metadata) = fanned(message.n).into_parts();
        ConsumerEffect::Produce(payload, metadata)
    }
}
// docs:end topic-sources

// ── contract-relay: a consumer that states a contract, a declared broker and parallel reading ──

/// The contract every reference states, from the same schema document: the fixtures' `order.v1`.
const ORDER_SCHEMA: &str = concat!(
    r#"{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","#,
    r#""required":["id","total"],"properties":{"id":{"type":"string"},"total":{"type":"number"}}}"#
);

fn order_contract() -> Contract {
    Contract::from_bytes(ORDER_SCHEMA.as_bytes(), "order.v1").expect("the order schema is JSON")
}

// docs:start contract-relay
/// Reads `conformance-contracts` as `order.v1` on broker `legacy`, partitions in parallel, and publishes one more.
pub struct ContractRelay;

impl Consumer for ContractRelay {
    type Message = Fanned;
    const COMPONENT_ID: &'static str = "contract-relay";

    fn source() -> Source {
        Source::topic("conformance-contracts")
            .contract(order_contract())
            .broker("legacy")
            .parallel()
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn produces() -> Option<Publication> {
        Some(
            Publication::to("conformance-contracted")
                .contract(order_contract())
                .broker("legacy"),
        )
    }

    fn on_message(message: Fanned, _: &Context) -> ConsumerEffect {
        let (payload, _, metadata) = fanned(message.n + 1).into_parts();
        ConsumerEffect::Produce(payload, metadata)
    }
}
// docs:end contract-relay

// ── cart-graph and profile-graph: graph consumers over each kind of entity ──

/// The example's cart graph, published to the topic the suite reads.
pub struct ConformanceCartGraph;

impl GraphConsumer for ConformanceCartGraph {
    type Message = ShoppingCartEvent;
    const COMPONENT_ID: &'static str = "cart-graph";
    const TOPIC: &'static str = "conformance-graph";

    fn source() -> Source {
        Source::of(ShoppingCart)
    }

    fn on_message(event: ShoppingCartEvent, ctx: &Context) -> GraphEffect {
        CartGraph::on_message(event, ctx)
    }

    fn on_deleted(ctx: &Context) -> GraphEffect {
        CartGraph::on_deleted(ctx)
    }
}

/// A node per profile, at the state's revision, and its tombstone when the profile is deleted.
pub struct ProfileGraph;

impl GraphConsumer for ProfileGraph {
    type Message = ProfileState;
    const COMPONENT_ID: &'static str = "profile-graph";
    const TOPIC: &'static str = "conformance-profile-graph";

    fn source() -> Source {
        Source::of(Profile)
    }

    fn on_message(profile: ProfileState, ctx: &Context) -> GraphEffect {
        graph::publish([graph::node(format!("profile:{}", ctx.entity_id()))
            .label("Profile")
            .property("name", profile.name)])
    }

    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!(
            "profile:{}",
            ctx.entity_id()
        ))])
    }
}

// ── reminder: a timed action ──

pub struct Reminder;

impl TimedAction for Reminder {
    const COMPONENT_ID: &'static str = "reminder";

    fn actions() -> Actions<Reminder> {
        Actions::new()
            .action("remind", |id: String, ctx: &Context| {
                let _: String =
                    ctx.client()
                        .invoke(Conformance, &id, "record", "reminded".to_string())?;
                Ok(())
            })
            // Records the due time it was run for, as the runtime told it.
            .action("tick", |id: String, ctx: &Context| {
                let due = ctx.due().ok_or_else(|| {
                    CommandError::new(ErrorCode::Internal, "the runtime set no ankka.due")
                })?;
                let _: String = ctx.client().invoke(
                    Conformance,
                    &id,
                    "record",
                    format!("due:{}", due.epoch_millis()),
                )?;
                Ok(())
            })
    }
}

// ── assistant: an agent whose tool acts through the client ──

#[derive(Debug, Deserialize)]
pub struct LookupArguments {
    pub id: String,
}

pub struct ConformanceAssistant;

impl ConformanceAssistant {
    fn ask(question: String, _: &Context) -> AgentEffect {
        agent::system_message("You are helpful.")
            .user_message(question)
            .tools(["lookup"])
            .guardrails(["no-secrets"])
            .then_reply()
    }

    fn lookup(args: LookupArguments, ctx: &Context) -> Result<String, String> {
        if args.id.is_empty() {
            return Err("an id is needed".to_string());
        }
        let client = ctx.client();
        let _: String = client
            .invoke(Conformance, &args.id, "record", "looked-up".to_string())
            .map_err(|e| e.message)?;
        let count: i32 = client
            .invoke(Conformance, &args.id, "count", ())
            .map_err(|e| e.message)?;
        Ok(format!("count for {} is {count}", args.id))
    }

    fn no_secrets(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
        if text.contains("sk-") {
            let stage = if stage == Stage::Input {
                "input"
            } else {
                "output"
            };
            Err(format!("{stage} rejected by no-secrets"))
        } else {
            Ok(())
        }
    }
}

impl Agent for ConformanceAssistant {
    const COMPONENT_ID: &'static str = "assistant";

    fn handlers() -> AgentHandlers<ConformanceAssistant> {
        AgentHandlers::new().command("ask", ConformanceAssistant::ask)
    }

    fn tools() -> Tools<ConformanceAssistant> {
        Tools::new().tool(
            "lookup",
            "Looks up how many things were recorded under an id.",
            Schema::object().string("id", "the id things were recorded under"),
            ConformanceAssistant::lookup,
        )
    }

    fn guardrails() -> Guardrails<ConformanceAssistant> {
        Guardrails::new().guardrail("no-secrets", ConformanceAssistant::no_secrets)
    }
}

// ── answerer: an autonomous agent, whose tool acts through the client ──

// docs:start autonomous-task-type
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Answer {
    pub answer: String,
    pub sources: Vec<String>,
}

/// The one task type the answerer takes: an answer, and what it was drawn from.
pub fn answer() -> TaskType<Answer> {
    TaskType::new(
        "answer",
        "Answer a question, citing what you looked up",
        Schema::object()
            .string("answer", "the answer")
            .string_array("sources", "what the answer was drawn from"),
    )
    .rule("cites-sources", |a: &Answer, _: &Context| {
        if a.sources.is_empty() {
            Verdict::rejected("sources must not be empty")
        } else {
            Verdict::Accepted
        }
    })
    .rule("steady", steady)
}
// docs:end autonomous-task-type

/// Panics the first time it sees "flaky-once": a rule that fails once, then decides. A module
/// instance keeps nothing between calls, so the first time is remembered by the conformance entity.
fn steady(a: &Answer, ctx: &Context) -> Verdict {
    if a.answer == "flaky-once" {
        let client = ctx.client();
        let seen: i32 = client
            .invoke(Conformance, "steady-flaky-once", "count", ())
            .expect("the conformance entity counts");
        if seen == 0 {
            let _: String = client
                .invoke(
                    Conformance,
                    "steady-flaky-once",
                    "record",
                    "seen".to_string(),
                )
                .expect("the conformance entity records");
            panic!("the rule threw");
        }
    }
    Verdict::Accepted
}

// docs:start autonomous-agent
pub struct ConformanceAnswerer;

impl AutonomousAgent for ConformanceAnswerer {
    const COMPONENT_ID: &'static str = "answerer";
    const DESCRIPTION: &'static str = "Answers questions";

    fn accepts() -> Vec<TaskAcceptance> {
        vec![TaskAcceptance::new(answer(), 4)]
    }

    fn tools() -> Tools<ConformanceAnswerer> {
        Tools::new().tool(
            "lookup",
            "Looks up how many things were recorded under an id.",
            Schema::object().string("id", "the id things were recorded under"),
            ConformanceAssistant::lookup,
        )
    }

    fn guardrails() -> Guardrails<ConformanceAnswerer> {
        Guardrails::new().guardrail("no-secrets", ConformanceAssistant::no_secrets)
    }
}
// docs:end autonomous-agent

// ── Endpoints ──

#[derive(Debug, Serialize)]
pub struct Echo {
    pub a: Vec<String>,
    pub b: Option<String>,
    pub headers: BTreeMap<String, String>,
}

/// What a call to another service came to, the same record in every language's reference.
#[derive(Debug, Default, Serialize)]
pub struct ServiceCallRecord {
    pub outcome: String,
    pub status: i32,
    #[serde(rename = "contentType")]
    pub content_type: String,
    pub body: String,
    pub answer: String,
    pub message: String,
}

impl ServiceCallRecord {
    fn failed(outcome: &str, error: &ServiceError) -> ServiceCallRecord {
        ServiceCallRecord {
            outcome: outcome.to_string(),
            message: error.to_string(),
            ..Default::default()
        }
    }
}

pub struct ConformanceEndpoint;

impl ConformanceEndpoint {
    /// A call to another service (protocol 1.10 for a module), as the case asks for it: `service`,
    /// `method` and `path` from the query, the body and every `X-Conformance-*` header sent on,
    /// and two headers no handler may send — the caller's and the host — added to show they never
    /// arrive. The answer is a record of what the client returned or failed with.
    fn service_call(request: &Request, body: String) -> Result<ServiceCallRecord, HttpProblem> {
        let required = |name: &str| {
            request
                .query(name)
                .map(str::to_string)
                .ok_or_else(|| HttpProblem::new(400, format!("the query needs '{name}'")))
        };
        let (service, method, path) =
            (required("service")?, required("method")?, required("path")?);
        let mut headers: Vec<(String, String)> = request
            .headers()
            .iter()
            .filter(|(name, _)| name.to_ascii_lowercase().starts_with("x-conformance-"))
            .cloned()
            .collect();
        headers.push(("X-Ankka-Caller".into(), "ankka://elsewhere/impostor".into()));
        headers.push(("Host".into(), "elsewhere".into()));
        let client = request
            .context()
            .services()
            .ok_or_else(|| HttpProblem::new(500, "a route may call another service"))?
            .service(&service);
        let answered = if request.query("mode") == Some("typed") {
            let sent: Vec<(&str, &str)> = headers
                .iter()
                .map(|(k, v)| (k.as_str(), v.as_str()))
                .collect();
            client
                .with_headers(&sent)
                .get_text(&path)
                .map(|text| ServiceCallRecord {
                    outcome: "response".into(),
                    status: 200,
                    body: text,
                    ..Default::default()
                })
        } else {
            let has_body = !body.is_empty();
            let options = RequestOptions {
                content_type: has_body.then(|| request.content_type().to_string()),
                body: has_body.then(|| body.into_bytes()),
                headers,
            };
            client
                .request(&method, &path, options)
                .map(|answer| ServiceCallRecord {
                    outcome: "response".into(),
                    status: i32::from(answer.status),
                    answer: answer.header("x-answer").unwrap_or_default().to_string(),
                    body: String::from_utf8_lossy(&answer.body).into_owned(),
                    content_type: answer.content_type,
                    ..Default::default()
                })
        };
        Ok(match answered {
            Ok(record) => record,
            Err(ServiceError::CallFailed { status, body, .. }) => ServiceCallRecord {
                outcome: "failed".into(),
                status: i32::from(status),
                body: String::from_utf8_lossy(&body).into_owned(),
                ..Default::default()
            },
            Err(e @ ServiceError::Unresolvable { .. }) => {
                ServiceCallRecord::failed("unresolvable", &e)
            }
            Err(e @ ServiceError::IdentityMismatch { .. }) => {
                ServiceCallRecord::failed("mismatch", &e)
            }
            Err(e @ ServiceError::Unanswered { .. }) => ServiceCallRecord::failed("unanswered", &e),
            Err(e @ ServiceError::Refused(_)) => ServiceCallRecord::failed("refused", &e),
        })
    }

    fn problems(_: &Request) -> Result<Vec<String>, HttpProblem> {
        // A module reports its problems in the runtime's log at start; one that started has none.
        Ok(Vec::new())
    }

    fn echo(request: &Request) -> Result<Echo, HttpProblem> {
        let a = request
            .query_pairs()
            .iter()
            .filter(|(k, _)| k == "a")
            .map(|(_, v)| v.clone())
            .collect();
        let header = |name: &str| request.header(name).unwrap_or_default().to_string();
        let headers = BTreeMap::from([
            ("x-one".to_string(), header("x-one")),
            ("x-two".to_string(), header("x-two")),
        ]);
        Ok(Echo {
            a,
            b: request.query("b").map(str::to_string),
            headers,
        })
    }

    fn status(request: &Request) -> Result<String, HttpProblem> {
        let code: u16 = request
            .path("code")
            .parse()
            .map_err(|_| HttpProblem::new(400, "a status code is a number"))?;
        Err(HttpProblem::new(code, format!("status {code} as asked")))
    }

    fn boom(_: &Request) -> Result<String, HttpProblem> {
        panic!("boom from the handler")
    }

    fn closed(_: &Request) -> Result<String, HttpProblem> {
        Ok("never reached".to_string())
    }

    // The secret store. The name is a query parameter because it may hold a slash.
    // docs:start secrets
    fn secrets(request: &Request) -> Result<ankka::Secrets, HttpProblem> {
        request
            .context()
            .secrets()
            .ok_or_else(|| HttpProblem::new(500, "an endpoint has a secret store"))
    }

    fn keep_secret(request: &Request, value: String) -> Result<Done, HttpProblem> {
        Self::secrets(request)?.put(request.query("name").unwrap_or_default(), &value)?;
        Ok(Done)
    }

    fn read_secret(request: &Request) -> Result<String, HttpProblem> {
        let name = request.query("name").unwrap_or_default();
        Self::secrets(request)?
            .get(name)?
            .ok_or_else(|| HttpProblem::new(404, format!("no secret '{name}'")))
    }

    fn remove_secret(request: &Request) -> Result<Done, HttpProblem> {
        Self::secrets(request)?.delete(request.query("name").unwrap_or_default())?;
        Ok(Done)
    }
    // docs:end secrets

    fn set_profile(request: &Request, name: String) -> Result<String, HttpProblem> {
        Ok(request
            .client()
            .invoke(Profile, request.path("id"), "set", name)?)
    }

    fn get_profile(request: &Request) -> Result<String, HttpProblem> {
        Ok(request
            .client()
            .invoke(Profile, request.path("id"), "get", ())?)
    }

    fn delete_profile(request: &Request) -> Result<String, HttpProblem> {
        Ok(request
            .client()
            .invoke(Profile, request.path("id"), "delete", ())?)
    }

    fn start_checkout(request: &Request, mode: String) -> Result<String, HttpProblem> {
        let _: Done =
            request
                .client()
                .invoke(CheckoutWorkflow, request.path("id"), "start", mode)?;
        Ok("started".to_string())
    }

    fn checkout_status(request: &Request) -> Result<String, HttpProblem> {
        let checkout: Checkout =
            request
                .client()
                .invoke(CheckoutWorkflow, request.path("id"), "status", ())?;
        Ok(checkout.status)
    }

    fn remind(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        let id = request.path("id");
        request.client().schedule(
            &format!("remind-{id}"),
            Duration::of_seconds(1),
            Reminder,
            None,
            "remind",
            id.to_string(),
        )?;
        Ok(Done)
    }

    fn set_recurring(
        request: &Request,
        delay: Duration,
        period: Duration,
    ) -> Result<Done, HttpProblem> {
        let id = request.path("id");
        request.client().schedule_recurring(
            &format!("recur-{id}"),
            delay,
            period,
            Reminder,
            "tick",
            id.to_string(),
        )?;
        Ok(Done)
    }

    /// A recurring timer: due at once, then every second.
    fn recur(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        Self::set_recurring(request, Duration::ZERO, Duration::of_seconds(1))
    }

    /// The same timer set again, with a delay a replacement would be first due after.
    fn recur_again(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        Self::set_recurring(request, Duration::of_seconds(60), Duration::of_seconds(1))
    }

    fn recur_cancel(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        let id = request.path("id");
        request.client().cancel(&format!("recur-{id}"))?;
        Ok(Done)
    }

    /// A period of zero: refused before anything is sent, a 400 naming the timer.
    fn recur_refused(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        Self::set_recurring(request, Duration::ZERO, Duration::ZERO)
    }

    fn ask(request: &Request, question: String) -> Result<String, HttpProblem> {
        Ok(request.client().invoke(
            ConformanceAssistant,
            request.path("session"),
            "ask",
            question,
        )?)
    }

    // A personal field: written, read back, and found by its lookup token (protocol 1.15).
    fn join_member(request: &Request, email: String) -> Result<String, HttpProblem> {
        Ok(request
            .client()
            .invoke(Member, request.path("id"), "join", email)?)
    }

    fn member_email(request: &Request) -> Result<String, HttpProblem> {
        Ok(request
            .client()
            .invoke(Member, request.path("id"), "email", ())?)
    }

    fn members_by_email(request: &Request) -> Result<Vec<String>, HttpProblem> {
        let token = ankka::personal::lookup_token_of(&request.path("email").to_string())?;
        let rows: Vec<MemberRow> =
            request
                .client()
                .ask(MemberRows, "by-email", &[("email", token.as_str())])?;
        Ok(rows.into_iter().map(|row| row.member_id).collect())
    }

    fn count(request: &Request) -> Result<i32, HttpProblem> {
        Ok(request
            .client()
            .invoke(Conformance, request.path("id"), "count", ())?)
    }

    /// A handler that answers nothing, so it is sent and not waited for; the route says so with
    /// 204.
    fn no_reply(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        request
            .client()
            .send(Conformance, request.path("id"), "no-reply", ())?;
        Ok(Done)
    }

    /// The generic forwarder: the body is the handler's input as text; the reply comes back as text.
    fn forward(request: &Request, body: String) -> Result<String, HttpProblem> {
        let (id, handler) = (request.path("id"), request.path("handler"));
        Ok(request.client().invoke(Conformance, id, handler, body)?)
    }

    /// What the `config` import answers for `name`: a descriptor's variable, or 404 for one that is
    /// unset or that the platform keeps from the module.
    fn config(request: &Request) -> Result<String, HttpProblem> {
        let name = request.path("name");
        ankka::config(name).ok_or_else(|| HttpProblem::new(404, format!("{name} is not set")))
    }
}

impl Endpoint for ConformanceEndpoint {
    const ENDPOINT_ID: &'static str = "ConformanceEndpoint";
    const PREFIX: &'static str = "/conformance";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<ConformanceEndpoint> {
        Routes::new()
            .get("/problems", ConformanceEndpoint::problems)
            .post("/service-call", ConformanceEndpoint::service_call)
            .post("/secrets", ConformanceEndpoint::keep_secret)
            .get("/secrets", ConformanceEndpoint::read_secret)
            .delete("/secrets", ConformanceEndpoint::remove_secret)
            .get("/echo", ConformanceEndpoint::echo)
            .get("/status/{code}", ConformanceEndpoint::status)
            .get("/boom", ConformanceEndpoint::boom)
            // A route whose acl differs from its endpoint's: /conformance admits everyone, this one
            // admits nobody, and the routes declared around it are unaffected.
            .get("/closed", ConformanceEndpoint::closed)
            .with_acl(Acl::DenyAll)
            .post("/profile/{id}", ConformanceEndpoint::set_profile)
            .get("/profile/{id}", ConformanceEndpoint::get_profile)
            .delete("/profile/{id}", ConformanceEndpoint::delete_profile)
            .post("/checkout/{id}", ConformanceEndpoint::start_checkout)
            .get("/checkout/{id}", ConformanceEndpoint::checkout_status)
            .post("/remind/{id}", ConformanceEndpoint::remind)
            .post("/recur/{id}", ConformanceEndpoint::recur)
            .post("/recur/{id}/again", ConformanceEndpoint::recur_again)
            .post("/recur/{id}/cancel", ConformanceEndpoint::recur_cancel)
            .post("/recur-refused/{id}", ConformanceEndpoint::recur_refused)
            .post("/ask/{session}", ConformanceEndpoint::ask)
            .get("/config/{name}", ConformanceEndpoint::config)
            .post("/members/{id}", ConformanceEndpoint::join_member)
            .get("/members/{id}", ConformanceEndpoint::member_email)
            .get(
                "/members/by-email/{email}",
                ConformanceEndpoint::members_by_email,
            )
            .get("/{id}/count", ConformanceEndpoint::count)
            .post("/{id}/no-reply", ConformanceEndpoint::no_reply)
            .post("/{id}/{handler}", ConformanceEndpoint::forward)
    }
}

/// Caller-naming ACLs: the suite names callers through the local caller header.
pub struct CallersEndpoint;

impl CallersEndpoint {
    fn whoami(request: &Request) -> Result<String, HttpProblem> {
        Ok(match request.caller() {
            Caller::Service { project, name } => format!("service:{project}/{name}"),
            Caller::Gateway => "gateway".to_string(),
            Caller::Local => "local".to_string(),
        })
    }
}

impl Endpoint for CallersEndpoint {
    const ENDPOINT_ID: &'static str = "CallersEndpoint";
    const PREFIX: &'static str = "/callers";

    fn acl() -> Acl {
        Acl::Callers(vec![
            CallerMatcher::Internet,
            CallerMatcher::service("orders"),
        ])
    }

    fn routes() -> Routes<CallersEndpoint> {
        Routes::new()
            .get("/whoami", CallersEndpoint::whoami)
            .get("/self", |_: &Request| Ok("self".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::SelfService]))
    }
}

/// Autonomous agents: tasks run, read and cancelled; instances driven and read.
pub struct AutonomousEndpoint;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Ran {
    pub task_id: String,
    pub instance_id: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Created {
    pub task_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Create {
    #[serde(default)]
    pub instructions: String,
    #[serde(default)]
    pub depends_on: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct Accepted {
    pub accepted: Vec<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceState {
    pub phase: String,
    pub queued: Vec<String>,
    pub current_task: Option<String>,
}

impl AutonomousEndpoint {
    fn only_answer(request: &Request) -> Result<(), HttpProblem> {
        let task_type = request.path("taskType");
        if task_type == answer().name() {
            Ok(())
        } else {
            Err(HttpProblem::new(400, format!("no task type '{task_type}'")))
        }
    }

    // docs:start autonomous-run
    fn run_task(request: &Request, instructions: String) -> Result<Ran, HttpProblem> {
        AutonomousEndpoint::only_answer(request)?;
        let client = request.client();
        let task_id = client
            .autonomous_agent(ConformanceAnswerer)
            .run_single_task(&answer(), instructions)?;
        let task = client.task(&task_id).get_as(&answer())?;
        let instance_id = task
            .assignee
            .map(|(_, instance)| instance)
            .unwrap_or_default();
        Ok(Ran {
            task_id,
            instance_id,
        })
    }
    // docs:end autonomous-run

    fn read_task(request: &Request) -> Result<Value, HttpProblem> {
        Ok(request.client().task(request.path("id")).get()?.record)
    }

    fn cancel_task(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        request.client().task(request.path("id")).cancel()?;
        Ok(Done)
    }

    /// `{"instructions": "...", "dependsOn": ["..."]}`: creates without running.
    fn create_task(request: &Request, body: Create) -> Result<Created, HttpProblem> {
        AutonomousEndpoint::only_answer(request)?;
        let task = NewTask::new(body.instructions).depends_on(body.depends_on);
        let task_id = request.client().tasks().create(&answer(), task)?;
        Ok(Created { task_id })
    }

    // docs:start autonomous-instance
    fn assign(request: &Request, task_ids: Vec<String>) -> Result<Accepted, HttpProblem> {
        let instance = request
            .client()
            .autonomous_agent(ConformanceAnswerer)
            .instance(request.path("instance"));
        let assignment = instance.assign(task_ids)?;
        Ok(Accepted {
            accepted: assignment.accepted,
        })
    }
    // docs:end autonomous-instance

    fn operate(request: &Request, (): ()) -> Result<Done, HttpProblem> {
        let instance = request
            .client()
            .autonomous_agent(ConformanceAnswerer)
            .instance(request.path("instance"));
        match request.path("op") {
            "suspend" => instance.suspend()?,
            "resume" => instance.resume()?,
            "terminate" => instance.terminate()?,
            op => return Err(HttpProblem::new(404, format!("no operation '{op}'"))),
        }
        Ok(Done)
    }

    fn state(request: &Request) -> Result<InstanceState, HttpProblem> {
        let state = request
            .client()
            .autonomous_agent(ConformanceAnswerer)
            .instance(request.path("instance"))
            .state()?;
        Ok(InstanceState {
            phase: state.phase,
            queued: state.queued,
            current_task: state.current_task,
        })
    }
}

impl Endpoint for AutonomousEndpoint {
    const ENDPOINT_ID: &'static str = "AutonomousEndpoint";
    const PREFIX: &'static str = "/autonomous";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<AutonomousEndpoint> {
        Routes::new()
            .post("/tasks/{taskType}", AutonomousEndpoint::run_task)
            .get("/tasks/{id}", AutonomousEndpoint::read_task)
            .post("/tasks/{id}/cancel", AutonomousEndpoint::cancel_task)
            .post("/tasks/{taskType}/create", AutonomousEndpoint::create_task)
            .post("/instances/{instance}/assign", AutonomousEndpoint::assign)
            .post("/instances/{instance}/{op}", AutonomousEndpoint::operate)
            .get("/instances/{instance}/state", AutonomousEndpoint::state)
    }
}

pub struct PrivateEndpoint;

impl Endpoint for PrivateEndpoint {
    const ENDPOINT_ID: &'static str = "PrivateEndpoint";
    const PREFIX: &'static str = "/private";

    fn acl() -> Acl {
        Acl::Authenticated
    }

    fn routes() -> Routes<PrivateEndpoint> {
        Routes::new()
            .get("/", |_: &Request| Ok("private".to_string()))
            .get("/me", |request: &Request| {
                let p = request
                    .principal()
                    .expect("an authenticated route is handed its principal");
                let mut roles = p.roles.clone();
                roles.sort();
                Ok(ankka::serde_json::json!({
                    "subject": p.subject,
                    "roles": roles,
                    "tier": p.claims.get("tier"),
                    "issuer": p.issuer,
                })
                .to_string())
            })
    }
}

/// The guest shape the suite asks for, through the `config` import.
pub fn shape() -> Shape {
    match ankka::config("ANKKA_CONFORMANCE_SHAPE").as_deref() {
        Some("stateful") => Shape::Stateful,
        _ => Shape::Stateless,
    }
}

/// The cart sample plus the conformance extras: what `conformance.sh` builds and runs the suite
/// against, in the shape `ANKKA_CONFORMANCE_SHAPE` names.
pub fn build() -> Service {
    let shape = shape();
    let service = Service::new("ankka-rust")
        .register_as(ShoppingCart, shape)
        .register(CartRows)
        .register_as(CheckoutWorkflow, shape)
        .register_as(Conformance, shape)
        .register_as(Profile, shape)
        .register_as(TreeNode, shape)
        .register(TreeRows)
        .register_as(Member, shape)
        .register(MemberRows)
        .register_as(JoinedLeft, shape)
        .register_as(JoinedRight, shape)
        .register(JoinedRows)
        .register(CheckoutRecorder);
    // The three that publish need a broker, and a runtime with none refuses a module that has
    // them. So they are registered where one is named, as the example's own are: the conformance
    // suite names one, and a cluster this module is deployed to without one does not.
    let service = match ankka::config("ANKKA_KAFKA_BOOTSTRAP_SERVERS") {
        Some(_) => service
            .register(CheckoutFanout)
            .register(TopicRows)
            .register(TopicRelay)
            .register(ContractRelay)
            .register(ConformanceCartGraph)
            .register(ProfileGraph),
        None => service,
    };
    let service = service
        .register(Reminder)
        .register(ConformanceAssistant)
        .register(ConformanceAnswerer)
        .endpoint(CartApi)
        .endpoint(TreeEndpoint)
        .endpoint(JoinedEndpoint)
        .endpoint(ConformanceEndpoint)
        .endpoint(PrivateEndpoint)
        .endpoint(CallersEndpoint)
        .endpoint(AutonomousEndpoint);
    // What a suite drives to see a module call another service from a consumer and a step, and
    // read the time and random bytes. Rust's alone, so they are registered only where the module
    // is told to: every reference declares the same components to the conformance suite.
    match ankka::config("ANKKA_CONFORMANCE_CALLS") {
        Some(_) => service
            .register_as(ServiceAsks, shape)
            .register(ServiceRelay)
            .register_as(ServiceSteps, shape)
            .endpoint(ServiceCallsEndpoint),
        None => service,
    }
}
