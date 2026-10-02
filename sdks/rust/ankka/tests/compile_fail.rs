//! What must not compile, so that it is a type error and not a convention someone forgot: a query
//! is given only a `ReadOnlyEffect`, so one that tries to persist does not compile; and a graph
//! consumer answers only with elements, so one that tries to produce a message of its own, under a
//! key of its own, does not either.

#[test]
fn what_must_not_compile_does_not() {
    trybuild::TestCases::new().compile_fail("tests/compile_fail/*.rs");
}
