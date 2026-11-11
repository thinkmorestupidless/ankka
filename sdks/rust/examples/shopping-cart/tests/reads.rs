//! The test kit reads a workflow's standing and how a copy stands from the running service (protocol
//! 1.24): the Rust rows of "the test kit reads ... from the running service" in
//! features/polyglot/standing.feature and features/replication/status.feature. A module reads through
//! its own handlers, so the test reads through the conformance reference's routes: the module built
//! with `--features conformance`, run as the installation `kit` beside one, `elsewhere`, it never
//! reaches, so the replicated vehicle has a home. With `--features slow`.

#[cfg(feature = "slow")]
mod slow {
    use std::path::PathBuf;
    use std::process::Command;

    use ankka::serde_json::{Value, json};
    use ankka::testkit::{AnkkaTestKit, Module};

    /// The conformance reference's module, copied out: every build of the example writes one file.
    fn reference() -> Module {
        let dir = env!("CARGO_MANIFEST_DIR");
        let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
        let status = Command::new(cargo)
            .current_dir(dir)
            .args(["build", "--release", "--target", "wasm32-unknown-unknown"])
            .args(["-p", "shopping-cart", "--features", "conformance"])
            .status()
            .expect("cargo runs");
        assert!(status.success(), "the conformance reference did not build");
        let built = PathBuf::from(dir)
            .join("../../target/wasm32-unknown-unknown/release/shopping_cart.wasm");
        let copy = std::env::temp_dir().join(format!("reads-{}.wasm", std::process::id()));
        std::fs::copy(&built, &copy).expect("the module is copied out");
        Module::at(copy).unwrap()
    }

    fn json_of(text: &str) -> Value {
        ankka::serde_json::from_str(text).unwrap_or_else(|e| panic!("{text}: {e}"))
    }

    #[test]
    fn the_test_kit_reads_a_standing_and_a_copy_from_the_running_service() {
        let env = vec![
            ("ANKKA_INSTALLATION".to_string(), "kit".to_string()),
            (
                "ANKKA_INSTALLATION_ELSEWHERE_DB_HOST".to_string(),
                "elsewhere.invalid".to_string(),
            ),
            ("ANKKA_CONFORMANCE_ERASURE".to_string(), "off".to_string()),
            // The reference declares authenticated routes, refused without an issuer; nothing here
            // presents a token, so the issuer is never asked.
            ("ANKKA_AUTH_ISSUERS".to_string(), "staff".to_string()),
            (
                "ANKKA_AUTH_STAFF_ISSUER".to_string(),
                "https://staff.invalid".to_string(),
            ),
            (
                "ANKKA_AUTH_STAFF_JWKS_URL".to_string(),
                "https://staff.invalid/keys".to_string(),
            ),
            ("ANKKA_AUTH_STAFF_AUDIENCE".to_string(), "kit".to_string()),
        ];
        let rt = AnkkaTestKit::start_with(reference(), env).unwrap();
        let http = rt.http();

        // A workflow that ended, one that failed past its retries, and one never started.
        let ended = http
            .post("/conformance/checkout-await/ir1")
            .text("ok")
            .send()
            .unwrap();
        assert_eq!(ended.status, 200, "{}", ended.text());
        let read = |id: &str| {
            let r = http
                .get(&format!("/conformance/checkout/{id}/lifecycle"))
                .send()
                .unwrap();
            assert_eq!(r.status, 200, "{}", r.text());
            json_of(&r.text())
        };
        assert_eq!(read("ir1")["status"], "completed");
        let failed = http
            .post("/conformance/checkout-await/ir2")
            .text("doomed")
            .send()
            .unwrap();
        assert_eq!(failed.status, 424, "{}", failed.text());
        let standing = read("ir2");
        assert_eq!(standing["status"], "failed");
        assert_eq!(standing["retries"]["charge"], 1);
        assert!(
            standing["failure"].as_str().is_some_and(|f| !f.is_empty()),
            "{standing}"
        );
        assert_eq!(read("ir3")["status"], "not-started");

        // A copy of the replicated vehicle, homed here; its position counts its event and the
        // platform's records of its home and its replication set.
        let step = http
            .post("/vehicles/ir4/steps")
            .text("frame")
            .send()
            .unwrap();
        assert_eq!(step.status, 200, "{}", step.text());
        let copy = http.get("/vehicles/ir4/replication").send().unwrap();
        assert_eq!(copy.status, 200, "{}", copy.text());
        let copy = json_of(&copy.text());
        assert_eq!(
            (&copy["home"], &copy["replicationSet"], &copy["position"]),
            (&json!("kit"), &json!(["kit"]), &json!(3)),
            "{copy}"
        );
        assert_eq!(copy["refused"], Value::Null, "{copy}");

        // An entity that is not replicated is refused, as a Scala handler is.
        let cart = json_of(
            &http
                .get("/conformance/cart/ir5/replication")
                .send()
                .unwrap()
                .text(),
        );
        assert_eq!(cart["refused"], "NOT_FOUND", "{cart}");
    }
}
