//! The HTTP endpoints page's examples: an endpoint that serves a website rather than an API, and
//! one that reads query parameters and headers. Not in the module's service.

use ankka::prelude::*;

const PAGE: &str = "<!doctype html><title>Account</title><h1>Your account</h1>";
const STYLESHEET: &[u8] = b"h1 { font-family: sans-serif; }";

// docs:start pages
/// Returns HTML, sends the browser elsewhere, and keeps a session.
pub struct PagesEndpoint;

impl PagesEndpoint {
    fn account(_: &Request) -> Result<Response, HttpProblem> {
        let cookie = "session=abc123; Path=/; HttpOnly";
        Ok(Response::html(PAGE).header("Set-Cookie", cookie))
    }

    /// `303 See Other`, the status that is safe after a form post; `.status(n)` changes it.
    fn logout(_: &Request, (): ()) -> Result<Response, HttpProblem> {
        Ok(Response::redirect("/"))
    }

    /// `Content-Type` is never a header here: a bytes response names its own.
    fn stylesheet(_: &Request) -> Result<Response, HttpProblem> {
        Ok(Response::bytes("text/css", STYLESHEET.to_vec()))
    }
}

impl Endpoint for PagesEndpoint {
    const ENDPOINT_ID: &'static str = "PagesEndpoint";
    const PREFIX: &'static str = "/site";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<PagesEndpoint> {
        Routes::new()
            .get("/account", PagesEndpoint::account)
            .post("/logout", PagesEndpoint::logout)
            .get("/style.css", PagesEndpoint::stylesheet)
    }
}
// docs:end pages

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct SearchResult {
    pub term: String,
    pub limit: i32,
    pub tags: Vec<String>,
    pub verbose: bool,
}

pub struct QueryEndpoint;

impl QueryEndpoint {
    // docs:start query
    /// Required, optional-with-default, repeated, and flag parameters.
    fn search(request: &Request) -> Result<SearchResult, HttpProblem> {
        // Absent is a 400 naming the parameter: a missing parameter the handler needed is the
        // caller's mistake, and no default stands in for it.
        let term = request
            .query("q")
            .ok_or_else(|| HttpProblem::new(400, "missing query parameter 'q'"))?;
        let limit = match request.query("limit") {
            None => 20,
            Some(given) => given
                .parse()
                .map_err(|_| HttpProblem::new(400, format!("'limit' is not a number: {given}")))?,
        };
        // Every value of a repeated parameter, in order.
        let tags = request
            .query_pairs()
            .iter()
            .filter(|(name, _)| name == "tag")
            .map(|(_, value)| value.clone())
            .collect();
        // A flag: `?verbose` and `?verbose=true` agree.
        let verbose = matches!(request.query("verbose"), Some("" | "true"));
        Ok(SearchResult {
            term: term.to_string(),
            limit,
            tags,
            verbose,
        })
    }
    // docs:end query

    // docs:start header
    /// Headers.
    fn trace(request: &Request) -> Result<String, HttpProblem> {
        Ok(request.header("X-Trace-Id").unwrap_or("none").to_string())
    }
    // docs:end header
}

impl Endpoint for QueryEndpoint {
    const ENDPOINT_ID: &'static str = "QueryEndpoint";
    const PREFIX: &'static str = "/search";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<QueryEndpoint> {
        Routes::new()
            .get("/", QueryEndpoint::search)
            .get("/trace", QueryEndpoint::trace)
    }
}
