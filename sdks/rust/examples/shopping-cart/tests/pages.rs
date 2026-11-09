//! The website's routes and the query endpoint with nothing running.

use ankka::testkit::EndpointTestKit;
use shopping_cart::pages::{PagesEndpoint, QueryEndpoint, SearchResult};

#[test]
fn the_site_serves_a_page_with_a_cookie_redirects_after_logout_and_names_its_stylesheets_type() {
    let kit = EndpointTestKit::<PagesEndpoint>::new();
    let page = kit.get("/site/account");
    assert_eq!(page.status, 200);
    assert!(page.content_type.starts_with("text/html"));
    assert!(page.text().contains("<h1>Your account</h1>"));
    let out = kit.post("/site/logout", &());
    assert_eq!(out.status, 303);
    let css = kit.get("/site/style.css");
    assert_eq!((css.status, css.content_type.as_str()), (200, "text/css"));
}

#[test]
fn query_parameters_are_required_defaulted_repeated_and_flags() {
    let kit = EndpointTestKit::<QueryEndpoint>::new();
    let found: SearchResult = kit
        .request("GET", "/search/")
        .query("q", "pens")
        .query("tag", "office")
        .query("tag", "blue")
        .query("verbose", "")
        .send()
        .json()
        .unwrap();
    assert_eq!(
        found,
        SearchResult {
            term: "pens".into(),
            limit: 20,
            tags: vec!["office".into(), "blue".into()],
            verbose: true,
        }
    );
    // Absent is a 400 naming the parameter; unparseable likewise.
    let missing = kit.get("/search/");
    assert_eq!(missing.status, 400);
    assert!(missing.text().contains("'q'"));
    let bad = kit
        .request("GET", "/search/")
        .query("q", "pens")
        .query("limit", "abc")
        .send();
    assert_eq!(bad.status, 400);
}

#[test]
fn a_header_is_read_from_the_request() {
    let kit = EndpointTestKit::<QueryEndpoint>::new();
    assert_eq!(kit.get("/search/trace").text(), "none");
    let traced = kit
        .request("GET", "/search/trace")
        .header("X-Trace-Id", "t-1")
        .send();
    assert_eq!(traced.text(), "t-1");
}
