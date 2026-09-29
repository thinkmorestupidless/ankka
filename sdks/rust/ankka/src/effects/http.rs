//! What an endpoint's route answers: a value, encoded by its type's default codec, or a
//! [`Response`] built by hand; and [`HttpProblem`] for a status other than success.
//!
//! A value that is only an acknowledgement — [`Done`](crate::Done) or `()` — answers
//! `204 No Content`, as every ankka endpoint does; any other value answers `200` with its encoding.

use std::fmt;

use serde::Serialize;

use crate::codec::{Auto, ContentType};
use crate::effects::CommandError;

/// A response with a status, a content type, a body and headers of the route's choosing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Response {
    /// The HTTP status.
    pub status: u16,
    /// The body's content type; empty for no body.
    pub content_type: String,
    /// The body.
    pub body: Vec<u8>,
    /// Headers beyond the content type, in order.
    pub headers: Vec<(String, String)>,
}

impl Response {
    /// `200` with `value` encoded by its default codec.
    pub fn json<T: Serialize + 'static>(value: &T) -> Result<Response, HttpProblem> {
        let codec = Auto::<T>::new();
        let body = codec
            .encode_value(value)
            .map_err(|e| HttpProblem::new(500, format!("the reply does not encode: {e}")))?;
        Ok(Response::with(
            200,
            codec.form().content_type().as_str(),
            body,
        ))
    }

    /// `200` with `text` as `text/plain`.
    pub fn text(text: impl Into<String>) -> Response {
        Response::with(200, ContentType::Text.as_str(), text.into().into_bytes())
    }

    /// `200` with `html` as `text/html; charset=utf-8`.
    pub fn html(html: impl Into<String>) -> Response {
        Response::with(200, "text/html; charset=utf-8", html.into().into_bytes())
    }

    /// `200` with `body` under a content type of the route's naming.
    pub fn bytes(content_type: impl Into<String>, body: Vec<u8>) -> Response {
        Response::with(200, content_type, body)
    }

    /// `303 See Other` to `location`.
    pub fn redirect(location: impl Into<String>) -> Response {
        Response::with(303, "", Vec::new()).header("Location", location)
    }

    /// `204 No Content`.
    pub fn no_content() -> Response {
        Response::with(204, "", Vec::new())
    }

    /// The same response with another status.
    pub fn status(mut self, status: u16) -> Response {
        self.status = status;
        self
    }

    /// The same response with one more header.
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Response {
        self.headers.push((name.into(), value.into()));
        self
    }

    fn with(status: u16, content_type: impl Into<String>, body: Vec<u8>) -> Response {
        Response {
            status,
            content_type: content_type.into(),
            body,
            headers: Vec::new(),
        }
    }
}

/// What a route's handler may answer: a [`Response`], or any value its default codec can encode.
pub trait IntoResponse {
    /// The response this value answers.
    fn into_response(self) -> Result<Response, HttpProblem>;
}

impl IntoResponse for Response {
    fn into_response(self) -> Result<Response, HttpProblem> {
        Ok(self)
    }
}

impl<T: Serialize + 'static> IntoResponse for T {
    fn into_response(self) -> Result<Response, HttpProblem> {
        let codec = Auto::<T>::new();
        if matches!(codec.form().manifest().as_str(), "done" | "unit") {
            return Ok(Response::no_content());
        }
        Response::json(&self)
    }
}

/// A route's refusal: answered with `status` and `message` as `text/plain`. A handler returns it
/// with `?` on a component's refusal, which answers that refusal's status, or builds one itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpProblem {
    /// The HTTP status.
    pub status: u16,
    /// What went wrong, for the caller.
    pub message: String,
}

impl HttpProblem {
    /// A refusal with `status` and `message`.
    pub fn new(status: u16, message: impl Into<String>) -> HttpProblem {
        HttpProblem {
            status,
            message: message.into(),
        }
    }
}

impl fmt::Display for HttpProblem {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} {}", self.status, self.message)
    }
}

impl std::error::Error for HttpProblem {}

/// A component's refusal, answered with its code's status.
impl From<CommandError> for HttpProblem {
    fn from(error: CommandError) -> HttpProblem {
        HttpProblem::new(error.code.http_status(), error.message)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Done;

    #[derive(Serialize)]
    struct Row {
        id: String,
    }

    #[test]
    fn a_value_answers_its_encoding_and_an_acknowledgement_no_content() {
        let row = Row { id: "r1".into() }.into_response().unwrap();
        assert_eq!(
            (row.status, row.content_type.as_str(), row.body.as_slice()),
            (200, "application/json", br#"{"id":"r1"}"#.as_slice())
        );
        let count = 3_i32.into_response().unwrap();
        assert_eq!(
            (count.status, count.content_type.as_str()),
            (200, "text/plain")
        );
        assert_eq!(count.body, b"3");
        assert_eq!(Done.into_response().unwrap().status, 204);
        assert_eq!(().into_response().unwrap().status, 204);
    }

    #[test]
    fn a_refusal_answers_its_code_status() {
        let problem: HttpProblem =
            CommandError::new(crate::effects::ErrorCode::Conflict, "checked out").into();
        assert_eq!(problem, HttpProblem::new(409, "checked out"));
        let redirect = Response::redirect("/next");
        assert_eq!(redirect.status, 303);
        assert_eq!(redirect.headers, vec![("Location".into(), "/next".into())]);
    }
}
