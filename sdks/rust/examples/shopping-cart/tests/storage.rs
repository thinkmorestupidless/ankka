//! The storage client through the runtime: the conformance reference's `/conformance/storage`
//! routes against the runtime's directory store, which the integration kit keeps on the host. What
//! a handler keeps is read from the directory; what the test seeds there a handler reads; both
//! outlive a restart. Needs Docker and the runtime image (`--features slow`).

#[cfg(feature = "slow")]
mod slow {
    use std::path::{Path, PathBuf};
    use std::process::Command;

    use ankka::serde_json::Value;
    use ankka::testkit::{AnkkaTestKit, Module};

    /// The example built as the conformance reference, copied out of the one file every build of
    /// the example writes, so another build cannot replace it under the test.
    fn reference() -> Module {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".to_string());
        let built = Command::new(cargo)
            .current_dir(&dir)
            .args(["build", "--release", "--target", "wasm32-unknown-unknown"])
            .args(["-p", "shopping-cart", "--features", "conformance"])
            .status()
            .expect("cargo runs");
        assert!(built.success(), "the conformance reference did not build");
        let workspace = dir.join("../..");
        let module = workspace.join("target/wasm32-unknown-unknown/release/shopping_cart.wasm");
        let copy = std::env::temp_dir().join(format!(
            "shopping-cart-reference-{}.wasm",
            std::process::id()
        ));
        std::fs::copy(Path::new(&module), &copy).expect("the module is copied out");
        Module::at(copy).unwrap()
    }

    fn record(body: &[u8]) -> Value {
        ankka::serde_json::from_slice(body).expect("a storage record")
    }

    #[test]
    fn objects_kept_through_the_runtime_are_the_directory_stores_and_outlive_a_restart() {
        // The reference has an authenticated route, so the runtime needs an issuer to host it;
        // its keys are fetched on first use, which nothing here asks for.
        let issuer = [
            ("ANKKA_AUTH_ISSUERS", "test"),
            ("ANKKA_AUTH_TEST_ISSUER", "https://issuer.invalid"),
            ("ANKKA_AUTH_TEST_JWKS_URL", "https://issuer.invalid/jwks"),
            ("ANKKA_AUTH_TEST_AUDIENCE", "conformance"),
        ];
        let env = issuer
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        let mut rt = AnkkaTestKit::start_with(reference(), env).unwrap();

        let kept = rt
            .http()
            .post("/conformance/storage?name=notes%2Fa.jpg")
            .bytes(b"hello", "image/jpeg")
            .send()
            .unwrap();
        assert_eq!(kept.status, 200, "{}", kept.text());
        assert_eq!(record(&kept.body)["outcome"], "ok", "{}", kept.text());
        let (content_type, body) = rt
            .storage()
            .get(None, "notes/a.jpg")
            .expect("kept in the directory");
        assert_eq!(
            (content_type.as_str(), body.as_slice()),
            ("image/jpeg", &b"hello"[..])
        );

        rt.storage()
            .put(None, "seeded/one.txt", b"from the test", "text/plain")
            .unwrap();
        rt.restart().unwrap();

        let got = rt
            .http()
            .get("/conformance/storage?op=get&name=seeded%2Fone.txt")
            .send()
            .unwrap();
        let got = record(&got.body);
        assert_eq!(
            (&got["outcome"], &got["contentType"], &got["text"]),
            (
                &Value::from("ok"),
                &Value::from("text/plain"),
                &Value::from("from the test")
            ),
            "{got}"
        );
        let listed = rt
            .http()
            .get("/conformance/storage?op=list&prefix=")
            .send()
            .unwrap();
        assert_eq!(
            record(&listed.body)["names"],
            ankka::serde_json::json!(["notes/a.jpg", "seeded/one.txt"])
        );
        assert_eq!(
            rt.storage().names(None),
            vec!["notes/a.jpg", "seeded/one.txt"]
        );
    }
}
