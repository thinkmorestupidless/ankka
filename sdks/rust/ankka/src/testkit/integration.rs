//! The integration testkit: the service's module, the real runtime, a real Postgres.
//!
//! [`Module::build`] builds the package under test for `wasm32-unknown-unknown`, and
//! [`AnkkaTestKit::start`] starts Postgres with the platform's schema — copied out of the runtime
//! image, so a test can never pass against a schema the platform does not have — and the runtime
//! image with the module bind-mounted read-only where it loads it from. [`http`](AnkkaTestKit::http)
//! talks to the runtime's HTTP port, where the module's routes are served; [`restart`](AnkkaTestKit::restart)
//! replaces the runtime against the same database, which is how a test proves durability rather
//! than caching.
//!
//! Needs Docker. The runtime image is `$ANKKA_SIDECAR_IMAGE` when that is set; otherwise a released
//! crate uses the image published with it, `ghcr.io/thinkmorestupidless/ankka-sidecar:<the crate's
//! version>`, and an unreleased one (version `0.0.0`, a checkout of the ankka repository) uses
//! `ankka-sidecar:latest`, the image `sbt sidecar/Docker/publishLocal` builds.
//!
//! ```ignore
//! let mut rt = AnkkaTestKit::start(Module::build()?)?;
//! let r = rt.http().post("/carts/cart-1/items").json(&item).send()?;
//! rt.restart()?;
//! ```

use std::fmt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use testcontainers::core::{AccessMode, IntoContainerPort, Mount, WaitFor};
use testcontainers::runners::SyncRunner;
use testcontainers::{Container, GenericImage, ImageExt};

use super::TestResponse;

/// The Postgres the kit starts, as the platform's own compose file names it.
pub const POSTGRES_IMAGE: &str = "postgres:17-alpine";

/// The port the runtime serves HTTP on inside its container.
pub const HTTP_PORT: u16 = 9000;

/// Where the runtime loads the module from inside its container.
pub const MODULE_PATH: &str = "/module/service.wasm";

const PUBLISHED_RUNTIME: &str = "ghcr.io/thinkmorestupidless/ankka-sidecar";
const READY_TIMEOUT: Duration = Duration::from_secs(60);

/// Why the kit could not do what it was asked, with whatever explains it attached.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TestkitError(pub String);

impl fmt::Display for TestkitError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for TestkitError {}

fn error(what: impl fmt::Display) -> TestkitError {
    TestkitError(what.to_string())
}

/// The runtime image the kit starts: see the module's documentation. A release publishes the crate
/// and the runtime image under one version, so the crate's own version names the image it was
/// tested with.
pub fn runtime_image() -> String {
    match std::env::var("ANKKA_SIDECAR_IMAGE") {
        Ok(image) if !image.is_empty() => image,
        _ => match env!("CARGO_PKG_VERSION") {
            "0.0.0" => "ankka-sidecar:latest".to_string(),
            version => format!("{PUBLISHED_RUNTIME}:{version}"),
        },
    }
}

/// `name:tag` split where Docker splits it: at the last `:` after the last `/`.
fn split_image(image: &str) -> (String, String) {
    let slash = image.rfind('/').map_or(0, |i| i + 1);
    match image[slash..].rfind(':') {
        Some(colon) => (
            image[..slash + colon].to_string(),
            image[slash + colon + 1..].to_string(),
        ),
        None => (image.to_string(), "latest".to_string()),
    }
}

fn unique(prefix: &str) -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or_default();
    format!("{prefix}-{}-{nanos}", std::process::id())
}

// ── The module ───────────────────────────────────────────────────────────────

/// A service's built module: the file the runtime loads.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Module {
    path: PathBuf,
}

impl Module {
    /// Builds the package whose tests are running — `CARGO_PKG_NAME`, from `CARGO_MANIFEST_DIR`,
    /// both of which cargo sets for a test — with `cargo build --release --target
    /// wasm32-unknown-unknown`, and answers the module it wrote. From the package's own directory,
    /// so every `.cargo/config.toml` from there up applies, the workspace's stack size among them.
    pub fn build() -> Result<Module, TestkitError> {
        let package = std::env::var("CARGO_PKG_NAME")
            .map_err(|_| error("CARGO_PKG_NAME is not set: Module::build runs under cargo test"))?;
        let dir = std::env::var("CARGO_MANIFEST_DIR").map_err(|_| {
            error("CARGO_MANIFEST_DIR is not set: Module::build runs under cargo test")
        })?;
        Module::build_package(&package, Path::new(&dir))
    }

    /// Builds `package` from `dir` as [`build`](Self::build) does.
    pub fn build_package(package: &str, dir: &Path) -> Result<Module, TestkitError> {
        let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".to_string());
        let output = Command::new(cargo)
            .current_dir(dir)
            .args([
                "build",
                "--release",
                "--target",
                "wasm32-unknown-unknown",
                "-p",
                package,
            ])
            .args(["--message-format", "json-render-diagnostics"])
            .stderr(Stdio::inherit())
            .output()
            .map_err(|e| error(format!("cargo could not be run: {e}")))?;
        if !output.status.success() {
            return Err(error(format!("building the module of '{package}' failed")));
        }
        let stdout = String::from_utf8_lossy(&output.stdout);
        let module = stdout
            .lines()
            .filter_map(|line| serde_json::from_str::<serde_json::Value>(line).ok())
            .filter(|m| m["reason"] == "compiler-artifact")
            .filter(|m| {
                m["target"]["kind"]
                    .as_array()
                    .is_some_and(|k| k.iter().any(|k| k == "cdylib"))
            })
            .flat_map(|m| {
                m["filenames"]
                    .as_array()
                    .cloned()
                    .unwrap_or_default()
                    .into_iter()
                    .filter_map(|f| f.as_str().map(PathBuf::from))
                    .collect::<Vec<_>>()
            })
            .find(|f| f.extension().is_some_and(|e| e == "wasm"));
        module.map(|path| Module { path }).ok_or_else(|| {
            error(format!(
                "'{package}' built no module: it needs crate-type = [\"cdylib\"]"
            ))
        })
    }

    /// A module already built.
    pub fn at(path: impl AsRef<Path>) -> Result<Module, TestkitError> {
        let path = path
            .as_ref()
            .canonicalize()
            .map_err(|e| error(format!("no module at {}: {e}", path.as_ref().display())))?;
        Ok(Module { path })
    }

    /// Where the module is.
    pub fn path(&self) -> &Path {
        &self.path
    }
}

// ── The runtime and its database ─────────────────────────────────────────────

/// The platform's schema as files, copied out of `image` at `/opt/docker/ddl`.
fn copy_ddl(image: &str, into: &Path) -> Result<(), TestkitError> {
    let created = Command::new("docker")
        .args(["create", image])
        .output()
        .map_err(|e| error(format!("docker could not be run: {e}")))?;
    if !created.status.success() {
        return Err(error(format!(
            "the runtime image {image} is not available: {}",
            String::from_utf8_lossy(&created.stderr).trim()
        )));
    }
    let id = String::from_utf8_lossy(&created.stdout).trim().to_string();
    let copied = Command::new("docker")
        .args([
            "cp",
            &format!("{id}:/opt/docker/ddl/."),
            &into.display().to_string(),
        ])
        .status();
    let _ = Command::new("docker")
        .args(["rm", "-f", &id])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
    match copied {
        Ok(status) if status.success() => Ok(()),
        _ => Err(error(format!(
            "the schema could not be copied out of {image}"
        ))),
    }
}

fn logs(container: &Container<GenericImage>) -> String {
    let mut out = container.stdout_to_vec().unwrap_or_default();
    out.extend(container.stderr_to_vec().unwrap_or_default());
    String::from_utf8_lossy(&out).into_owned()
}

/// A container on the kit's network and database, serving HTTP: the runtime, or another image
/// started beside it.
pub struct Beside {
    container: Container<GenericImage>,
    http: Http,
}

impl Beside {
    /// Its HTTP port.
    pub fn http(&self) -> &Http {
        &self.http
    }

    /// Its log so far.
    pub fn logs(&self) -> String {
        logs(&self.container)
    }
}

/// Postgres with the platform's schema and, when running, the runtime hosting one module.
pub struct AnkkaTestKit {
    image: String,
    module: Module,
    network: String,
    database: String,
    env: Vec<(String, String)>,
    ddl: PathBuf,
    postgres: Option<Container<GenericImage>>,
    runtime: Option<Beside>,
}

impl AnkkaTestKit {
    /// Starts Postgres and the runtime image hosting `module`, ready to serve.
    pub fn start(module: Module) -> Result<AnkkaTestKit, TestkitError> {
        AnkkaTestKit::start_with(module, Vec::new())
    }

    /// As [`start`](Self::start), with `env` on the runtime's container.
    pub fn start_with(
        module: Module,
        env: Vec<(String, String)>,
    ) -> Result<AnkkaTestKit, TestkitError> {
        let image = runtime_image();
        let ddl = std::env::temp_dir().join(unique("ankka-ddl"));
        std::fs::create_dir_all(&ddl).map_err(error)?;
        // Postgres reads the directory as its own user, so it must be readable by others: on Linux
        // a bind mount keeps the host's permissions.
        set_readable(&ddl)?;
        let mut kit = AnkkaTestKit {
            image,
            module,
            network: unique("ankka-testkit"),
            database: unique("ankka-postgres"),
            env,
            ddl,
            postgres: None,
            runtime: None,
        };
        copy_ddl(&kit.image, &kit.ddl)?;
        kit.start_postgres()?;
        kit.start_runtime()?;
        Ok(kit)
    }

    fn start_postgres(&mut self) -> Result<(), TestkitError> {
        let (name, tag) = split_image(POSTGRES_IMAGE);
        let ready = "database system is ready to accept connections";
        let postgres = GenericImage::new(name, tag)
            .with_wait_for(WaitFor::message_on_either_std(ready))
            .with_network(self.network.clone())
            .with_container_name(self.database.clone())
            .with_env_var("POSTGRES_USER", "ankka")
            .with_env_var("POSTGRES_PASSWORD", "ankka")
            .with_env_var("POSTGRES_DB", "ankka")
            .with_mount(
                Mount::bind_mount(
                    self.ddl.display().to_string(),
                    "/docker-entrypoint-initdb.d",
                )
                .with_access_mode(AccessMode::ReadOnly),
            )
            .start()
            .map_err(|e| error(format!("postgres did not start: {e}")))?;
        // The image starts once to run the schema, then again to serve: the second "ready" is the
        // one that means it.
        let deadline = Instant::now() + READY_TIMEOUT;
        while logs(&postgres).matches(ready).count() < 2 {
            if Instant::now() > deadline {
                return Err(error(format!(
                    "postgres was not ready:\n{}",
                    logs(&postgres)
                )));
            }
            std::thread::sleep(Duration::from_millis(200));
        }
        self.postgres = Some(postgres);
        Ok(())
    }

    fn database_env(&self) -> Vec<(String, String)> {
        [
            ("ANKKA_DB_HOST", self.database.as_str()),
            ("ANKKA_DB_PORT", "5432"),
            ("ANKKA_DB_NAME", "ankka"),
            ("ANKKA_DB_USER", "ankka"),
            ("ANKKA_DB_PASSWORD", "ankka"),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
    }

    /// Starts `image` on this kit's network and database, serving HTTP on `port`, and waits for
    /// it to be healthy. Another service on the same journal — the Scala cart, say — to prove the
    /// journal is shared. Never run two against one database at once: each is a cluster of its own.
    pub fn start_beside(
        &self,
        image: &str,
        port: u16,
        env: Vec<(String, String)>,
    ) -> Result<Beside, TestkitError> {
        self.start_container(image, port, env, None)
    }

    fn start_container(
        &self,
        image: &str,
        port: u16,
        env: Vec<(String, String)>,
        module: Option<&Module>,
    ) -> Result<Beside, TestkitError> {
        let (name, tag) = split_image(image);
        let mut request = GenericImage::new(name, tag)
            .with_exposed_port(port.tcp())
            .with_network(self.network.clone())
            .with_env_var("ANKKA_HTTP_PORT", port.to_string());
        for (k, v) in self.database_env().into_iter().chain(env) {
            request = request.with_env_var(k, v);
        }
        if let Some(module) = module {
            request = request
                .with_env_var("ANKKA_WASM_MODULE", MODULE_PATH)
                .with_mount(
                    Mount::bind_mount(module.path().display().to_string(), MODULE_PATH)
                        .with_access_mode(AccessMode::ReadOnly),
                );
        }
        let container = request
            .start()
            .map_err(|e| error(format!("{image} did not start: {e}")))?;
        let host_port = container
            .get_host_port_ipv4(port.tcp())
            .map_err(|e| error(format!("{image} published no port {port}: {e}")))?;
        let beside = Beside {
            container,
            http: Http::new(format!("http://127.0.0.1:{host_port}")),
        };
        let deadline = Instant::now() + READY_TIMEOUT;
        let mut last = "no answer yet".to_string();
        while Instant::now() < deadline {
            match beside.http.get("/_ankka/health").send() {
                Ok(r) if r.status == 200 => return Ok(beside),
                Ok(r) => last = format!("HTTP {}", r.status),
                Err(e) => last = e.0,
            }
            if !beside.container.is_running().unwrap_or(false) {
                break;
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        Err(error(format!(
            "{image} was not ready within {}s ({last}); its log:\n{}",
            READY_TIMEOUT.as_secs(),
            beside.logs()
        )))
    }

    /// Starts the runtime hosting the module, if it is not running.
    pub fn start_runtime(&mut self) -> Result<(), TestkitError> {
        if self.runtime.is_none() {
            let runtime =
                self.start_container(&self.image, HTTP_PORT, self.env.clone(), Some(&self.module))?;
            self.runtime = Some(runtime);
        }
        Ok(())
    }

    /// Stops the runtime, leaving the database: every instance is gone from memory.
    pub fn stop_runtime(&mut self) {
        self.runtime = None;
    }

    /// A new runtime against the same database: the next read has to rebuild from the journal.
    pub fn restart(&mut self) -> Result<(), TestkitError> {
        self.stop_runtime();
        self.start_runtime()
    }

    /// The runtime's HTTP port, where the module's routes are served.
    ///
    /// Panics if the runtime is stopped.
    pub fn http(&self) -> &Http {
        self.runtime
            .as_ref()
            .expect("the runtime is stopped: start_runtime() first")
            .http()
    }

    /// The runtime's log so far; empty if it is stopped.
    pub fn runtime_logs(&self) -> String {
        self.runtime.as_ref().map(Beside::logs).unwrap_or_default()
    }
}

impl Drop for AnkkaTestKit {
    fn drop(&mut self) {
        self.runtime = None;
        self.postgres = None;
        let _ = std::fs::remove_dir_all(&self.ddl);
    }
}

#[cfg(unix)]
fn set_readable(dir: &Path) -> Result<(), TestkitError> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o755)).map_err(error)
}

#[cfg(not(unix))]
fn set_readable(_: &Path) -> Result<(), TestkitError> {
    Ok(())
}

// ── HTTP ─────────────────────────────────────────────────────────────────────

/// A small blocking HTTP client for one base address. Every status is an answer, not an error.
#[derive(Clone)]
pub struct Http {
    base: String,
    agent: ureq::Agent,
}

impl Http {
    fn new(base: String) -> Http {
        let agent = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_global(Some(Duration::from_secs(30)))
            .build()
            .into();
        Http { base, agent }
    }

    /// The base address.
    pub fn base(&self) -> &str {
        &self.base
    }

    /// A request to send.
    pub fn request(&self, method: &str, path: &str) -> HttpRequest<'_> {
        HttpRequest {
            http: self,
            method: method.to_ascii_uppercase(),
            path: path.to_string(),
            headers: Vec::new(),
            body: Vec::new(),
        }
    }

    /// `GET path`.
    pub fn get(&self, path: &str) -> HttpRequest<'_> {
        self.request("GET", path)
    }

    /// `POST path`.
    pub fn post(&self, path: &str) -> HttpRequest<'_> {
        self.request("POST", path)
    }

    /// `PUT path`.
    pub fn put(&self, path: &str) -> HttpRequest<'_> {
        self.request("PUT", path)
    }

    /// `DELETE path`.
    pub fn delete(&self, path: &str) -> HttpRequest<'_> {
        self.request("DELETE", path)
    }
}

/// A request being built for [`Http`].
pub struct HttpRequest<'h> {
    http: &'h Http,
    method: String,
    path: String,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl HttpRequest<'_> {
    /// A body of `value` as JSON.
    pub fn json<B: Serialize>(mut self, value: &B) -> Self {
        self.body = serde_json::to_vec(value).expect("the body serializes");
        self.header("content-type", "application/json")
    }

    /// A text body.
    pub fn text(mut self, text: &str) -> Self {
        self.body = text.as_bytes().to_vec();
        self.header("content-type", "text/plain")
    }

    /// A header.
    pub fn header(mut self, name: &str, value: &str) -> Self {
        self.headers.push((name.to_string(), value.to_string()));
        self
    }

    /// Sends it, answering whatever status came back.
    pub fn send(self) -> Result<TestResponse, TestkitError> {
        let url = format!("{}{}", self.http.base, self.path);
        let mut builder = ureq::http::Request::builder()
            .method(self.method.as_str())
            .uri(&url);
        for (name, value) in &self.headers {
            builder = builder.header(name, value);
        }
        let request = builder.body(self.body).map_err(error)?;
        let response = self
            .http
            .agent
            .run(request)
            .map_err(|e| error(format!("{} {url}: {e}", self.method)))?;
        let status = response.status().as_u16();
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_string();
        let body = response.into_body().read_to_vec().map_err(error)?;
        Ok(TestResponse {
            status,
            content_type,
            body,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_image_is_split_where_docker_splits_it() {
        assert_eq!(
            split_image("postgres:17-alpine"),
            ("postgres".into(), "17-alpine".into())
        );
        assert_eq!(
            split_image("ankka-sidecar"),
            ("ankka-sidecar".into(), "latest".into())
        );
        assert_eq!(
            split_image("localhost:5000/team/cart:1.2"),
            ("localhost:5000/team/cart".into(), "1.2".into())
        );
    }
}
