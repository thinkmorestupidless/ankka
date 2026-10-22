//! An MCP endpoint as a Rust module declares and runs it (protocol 1.20): what discovery says, a
//! tool's answers, references resolved into tools, the refusal of a runtime too old, and the client
//! keys a unit test talks to. The runtime's half is held by the conformance suite's `mcp-endpoint.*`.

use std::cell::RefCell;
use std::rc::Rc;

use ankka::abi::imports::{Import, NativeHost, with_native_host};
use ankka::prelude::*;
use ankka::proto;
use prost::Message;
use serde_json::Value;

#[derive(Serialize, Deserialize, Default, Clone)]
struct Count {
    n: i32,
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type")]
enum Counted {
    Added { by: i32 },
}

#[derive(Serialize, Deserialize)]
struct By {
    by: i32,
}

struct Counter;

impl EventSourcedEntity for Counter {
    type State = Count;
    type Event = Counted;
    const COMPONENT_ID: &'static str = "counter";

    fn empty_state(_: &str) -> Count {
        Count::default()
    }

    fn apply(count: Count, event: &Counted) -> Count {
        match event {
            Counted::Added { by } => Count { n: count.n + by },
        }
    }

    fn handlers() -> Handlers<Counter> {
        Handlers::new()
            .command("add", |_: &Count, by: By, _: &Context| {
                effects::persist(Counted::Added { by: by.by }).then_reply_value(Done)
            })
            .query("get", |c: &Count, _: (), _: &Context| {
                effects::reply(c.clone())
            })
    }
}

struct Orders;

impl Endpoint for Orders {
    const ENDPOINT_ID: &'static str = "orders";
    const PREFIX: &'static str = "/orders";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<Orders> {
        Routes::new().get("/{day}", |r: &Request| {
            if r.path("day") == "never" {
                Err(HttpProblem::new(404, "no orders that day"))
            } else {
                Ok(format!("orders of {}", r.path("day")))
            }
        })
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Named {
    cart_id: String,
}

struct CountMcp;

impl McpEndpoint for CountMcp {
    const ENDPOINT_ID: &'static str = "count-mcp";
    const PATH: &'static str = "/mcp";

    fn acl() -> Acl {
        Acl::AnyOf(vec![Acl::ClientKeys, Acl::Authenticated])
    }

    fn tools() -> McpTools<CountMcp> {
        McpTools::new()
            .tool(
                "who",
                "Says who asked",
                Schema::object().string("cartId", "The cart's id"),
                |args: Named, request: &McpRequest| {
                    Ok(serde_json::json!({
                        "cartId": args.cart_id,
                        "subject": request.principal().map(|p| p.subject.clone()),
                    }))
                },
            )
            .tool(
                "echo",
                "Answers its text",
                Schema::object().string("cartId", "Any text"),
                |args: Named, _: &McpRequest| Ok(args.cart_id),
            )
            .tool(
                "refuse",
                "Always refuses",
                Schema::object().integer("quantity", "How many"),
                |_: Value, _: &McpRequest| -> Result<Done, CommandError> {
                    Err(CommandError::new(
                        ErrorCode::BadRequest,
                        "quantity must be greater than zero",
                    ))
                },
            )
            .with_acl(Acl::Callers(vec![CallerMatcher::service("office")]))
            .command(
                "add",
                "Adds to a counter",
                Counter,
                "add",
                "counterId",
                Schema::object().integer("by", "How much"),
            )
            .command(
                "read",
                "Reads a counter",
                Counter,
                "get",
                "counterId",
                Schema::object(),
            )
            .route(
                "orders",
                "The day's orders",
                Orders::ENDPOINT_ID,
                "GET",
                "/{day}",
            )
    }
}

fn service() -> Service {
    Service::new("test")
        .register(Counter)
        .endpoint(Orders)
        .mcp_endpoint(CountMcp)
}

fn discover(service: &Service, protocol: &str) -> proto::Spec {
    service
        .discover(&proto::SidecarInfo {
            protocol_version: protocol.to_string(),
            runtime_version: String::new(),
        })
        .spec
        .expect("a spec")
}

fn call(service: &Service, tool: &str, arguments: &str) -> proto::mcp_tool_reply::Reply {
    service
        .mcp_tool(proto::McpToolRequest {
            endpoint_id: "count-mcp".into(),
            tool: tool.into(),
            arguments_json: arguments.into(),
            principal: Some(proto::Principal {
                subject: "acme".into(),
                claims: [("person".to_string(), "alice".to_string())].into(),
                ..Default::default()
            }),
            ..Default::default()
        })
        .reply
        .expect("a reply")
}

#[test]
fn discovery_declares_the_endpoint_its_tools_their_schemas_and_acls() {
    let service = service();
    assert!(service.problems().is_empty(), "{:?}", service.problems());
    let spec = discover(&service, "1.20");
    assert_eq!(spec.protocol_version, "1.22");
    let [endpoint] = spec.mcp_endpoints.as_slice() else {
        panic!("{:?}", spec.mcp_endpoints)
    };
    assert_eq!(
        (endpoint.id.as_str(), endpoint.path.as_str()),
        ("count-mcp", "/mcp")
    );
    let acl = endpoint.acl.clone().expect("an acl");
    assert!(acl.client_keys && acl.tokens && !acl.allow_all && !acl.deny_all);
    let names: Vec<&str> = endpoint.tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(
        names,
        vec!["who", "echo", "refuse", "add", "read", "orders"]
    );
    let add = &endpoint.tools[3];
    let schema: Value = serde_json::from_str(&add.input_schema_json).unwrap();
    assert_eq!(schema["properties"]["by"]["type"], "integer");
    assert_eq!(schema["properties"]["counterId"]["type"], "string");
    assert_eq!(schema["required"], serde_json::json!(["counterId", "by"]));
    let route: Value = serde_json::from_str(&endpoint.tools[5].input_schema_json).unwrap();
    assert_eq!(route["required"], serde_json::json!(["day"]));
    let refuse_acl = endpoint.tools[2].acl.clone().expect("the tool's own acl");
    assert_eq!(refuse_acl.callers.len(), 1);
    assert!(endpoint.tools[0].acl.is_none(), "unset is the endpoint's");
}

#[test]
#[should_panic(expected = "Run a runtime speaking 1.20 or later")]
fn a_runtime_from_before_mcp_endpoints_is_refused_naming_the_protocol_version() {
    let _ = discover(&service(), "1.19");
}

#[test]
fn a_service_without_an_mcp_endpoint_still_runs_on_an_older_runtime() {
    let spec = discover(&Service::new("test").register(Counter), "1.19");
    assert!(spec.mcp_endpoints.is_empty());
}

#[test]
fn an_object_answer_is_text_and_structured_and_the_principal_reaches_the_tool() {
    use proto::mcp_tool_reply::Reply;
    let Reply::Ok(answer) = call(&service(), "who", r#"{"cartId":"c1"}"#) else {
        panic!("not answered")
    };
    let text: Value = serde_json::from_str(&answer.text).unwrap();
    assert_eq!(text, serde_json::json!({"cartId": "c1", "subject": "acme"}));
    assert_eq!(
        answer.structured_json.as_deref(),
        Some(answer.text.as_str())
    );
}

#[test]
fn a_string_answer_is_its_own_text_with_no_structured_content() {
    use proto::mcp_tool_reply::Reply;
    assert_eq!(
        call(&service(), "echo", r#"{"cartId":"seven"}"#),
        Reply::Ok(proto::McpToolAnswer {
            text: "seven".into(),
            structured_json: None
        })
    );
}

#[test]
fn a_refusal_is_a_tool_error_with_its_message_and_an_unknown_tool_a_failure() {
    use proto::mcp_tool_reply::Reply;
    assert_eq!(
        call(&service(), "refuse", r#"{"quantity":0}"#),
        Reply::Error("quantity must be greater than zero".into())
    );
    let Reply::Error(message) = call(&service(), "echo", r#"{"cartId":7}"#) else {
        panic!("undecodable arguments are a tool error")
    };
    assert!(message.contains("echo"), "{message}");
    let Reply::Failure(failure) = call(&service(), "refund", "{}") else {
        panic!("an unknown tool is a failure")
    };
    let error = failure.error.unwrap();
    assert_eq!(error.code, proto::ErrorCode::NotFound as i32);
    assert!(error.message.contains("refund"), "{}", error.message);
}

/// Answers every invoke with `reply`, and keeps what it was asked.
struct Invokes {
    asked: Rc<RefCell<Vec<proto::InvokeRequest>>>,
    reply: proto::Payload,
}

impl NativeHost for Invokes {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        assert_eq!(import, Import::Invoke, "only invokes are expected");
        self.asked
            .borrow_mut()
            .push(proto::InvokeRequest::decode(request).unwrap());
        proto::InvokeReply {
            result: Some(proto::invoke_reply::Result::Reply(proto::outcome::Reply {
                payload: Some(self.reply.clone()),
                ..Default::default()
            })),
        }
        .encode_to_vec()
    }
}

#[test]
fn a_command_reference_calls_the_handler_with_the_id_and_the_other_arguments() {
    use proto::mcp_tool_reply::Reply;
    let asked = Rc::new(RefCell::new(Vec::new()));
    let host = Invokes {
        asked: asked.clone(),
        reply: proto::Payload {
            manifest: "done".into(),
            ..Default::default()
        },
    };
    let reply = with_native_host(host, || {
        call(&service(), "add", r#"{"counterId":"k1","by":3}"#)
    });
    assert_eq!(
        reply,
        Reply::Ok(proto::McpToolAnswer {
            text: "done".into(),
            structured_json: None
        })
    );
    let asked = asked.borrow();
    let [invoke] = asked.as_slice() else {
        panic!("{asked:?}")
    };
    assert_eq!(
        (
            invoke.component_id.as_str(),
            invoke.entity_id.as_str(),
            invoke.name.as_str()
        ),
        ("counter", "k1", "add")
    );
    let input: Value = serde_json::from_slice(&invoke.payload.clone().unwrap().data).unwrap();
    assert_eq!(input, serde_json::json!({"by": 3}));
}

#[test]
fn a_command_reference_with_no_other_argument_sends_nothing_and_answers_the_reply() {
    use proto::mcp_tool_reply::Reply;
    let asked = Rc::new(RefCell::new(Vec::new()));
    let host = Invokes {
        asked: asked.clone(),
        reply: proto::Payload {
            manifest: "Count".into(),
            data: br#"{"n":4}"#.to_vec(),
            ..Default::default()
        },
    };
    let reply = with_native_host(host, || call(&service(), "read", r#"{"counterId":"k1"}"#));
    let Reply::Ok(answer) = reply else {
        panic!("{reply:?}")
    };
    assert_eq!(answer.structured_json.as_deref(), Some(r#"{"n":4}"#));
    assert_eq!(
        asked.borrow()[0].payload.clone().unwrap().data,
        Vec::<u8>::new()
    );
}

#[test]
fn a_route_reference_runs_the_route_and_its_refusal_is_a_tool_error() {
    use proto::mcp_tool_reply::Reply;
    assert_eq!(
        call(&service(), "orders", r#"{"day":"monday"}"#),
        Reply::Ok(proto::McpToolAnswer {
            text: "orders of monday".into(),
            structured_json: None
        })
    );
    assert_eq!(
        call(&service(), "orders", r#"{"day":"never"}"#),
        Reply::Error("no orders that day".into())
    );
}

struct Dangling;

impl McpEndpoint for Dangling {
    const ENDPOINT_ID: &'static str = "dangling";
    const PATH: &'static str = "mcp";

    fn acl() -> Acl {
        Acl::AnyOf(vec![])
    }

    fn tools() -> McpTools<Dangling> {
        McpTools::new()
            .command("add", "Adds", Counter, "subtract", "id", Schema::object())
            .route("orders", "Orders", "missing", "GET", "/{day}")
            .route("orders", "Again", "orders", "GET", "/{day}")
            .tool(
                "bad name",
                " ",
                Schema::object(),
                |_: Value, _: &McpRequest| Ok(Done),
            )
    }
}

#[test]
fn every_problem_with_an_mcp_endpoint_is_reported_when_the_service_is_built() {
    let problems: Vec<String> = Service::new("test")
        .register(Counter)
        .mcp_endpoint(Dangling)
        .problems()
        .into_iter()
        .map(|p| p.message)
        .collect();
    for expected in [
        "names handler 'subtract' of 'counter'",
        "names endpoint 'missing'",
        "declares tool 'orders' twice",
        "tool name 'bad name'",
        "has no description",
        "path 'mcp' must start with '/'",
        "any of several must name at least one",
    ] {
        assert!(
            problems.iter().any(|p| p.contains(expected)),
            "{expected} in {problems:?}"
        );
    }
}

struct KeyedHttp;

impl Endpoint for KeyedHttp {
    const ENDPOINT_ID: &'static str = "keyed";
    const PREFIX: &'static str = "/keyed";

    fn acl() -> Acl {
        Acl::ClientKeys
    }

    fn routes() -> Routes<KeyedHttp> {
        Routes::new().post("/", |_: &Request, (): ()| Ok(Done))
    }
}

#[test]
fn client_keys_are_an_mcp_endpoints_acl_not_an_http_endpoints() {
    let problems = Service::new("test").endpoint(KeyedHttp).problems();
    let [problem] = problems.as_slice() else {
        panic!("{problems:?}")
    };
    assert!(
        problem.message.contains("acls of an MCP endpoint"),
        "{problem}"
    );
}

#[test]
fn client_keys_are_issued_listed_and_revoked_without_a_runtime() {
    let keys = Context::new("test", "", 0, Default::default())
        .with_client_keys()
        .client_keys()
        .expect("a context given the keys has them");
    let issued = keys
        .issue(
            IssueClientKey::new("acme", Expiry::Never)
                .scope(["get_cart"])
                .attribute("person", "alice")
                .label("Alice's assistant"),
        )
        .unwrap();
    assert!(
        !format!("{issued:?}").contains(&issued.client_key),
        "Debug shows no key"
    );
    let listed = keys.list().unwrap();
    let mine = listed.iter().find(|k| k.id == issued.id).expect("listed");
    assert_eq!(mine.subject, "acme");
    assert_eq!(
        mine.attributes.get("person").map(String::as_str),
        Some("alice")
    );
    assert_eq!(mine.scope, vec!["get_cart".to_string()]);
    assert!(mine.revoked_at.is_none());
    let shown = serde_json::to_string(&listed).unwrap();
    assert!(
        !shown.contains(&issued.client_key),
        "a listing never holds the key"
    );
    keys.revoke(&issued.id).unwrap();
    assert!(keys.list().unwrap()[0].revoked_at.is_some());
    let missing = keys.revoke("ck_nope").unwrap_err();
    assert_eq!(missing.code, ErrorCode::NotFound);
    let refused = keys
        .issue(IssueClientKey::new(" ", Expiry::Never))
        .unwrap_err();
    assert_eq!(refused.code, ErrorCode::BadRequest);
}

#[test]
fn an_entity_has_no_client_keys() {
    assert!(
        Context::new("counter", "c1", 0, Default::default())
            .client_keys()
            .is_none()
    );
}
