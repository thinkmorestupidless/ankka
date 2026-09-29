//! What must not compile: a query is given only a `ReadOnlyEffect`, so one that tries to persist
//! is a type error, not a convention someone forgot.

#[test]
fn a_query_cannot_persist() {
    trybuild::TestCases::new().compile_fail("tests/compile_fail/*.rs");
}
