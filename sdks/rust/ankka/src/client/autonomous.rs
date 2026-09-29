//! Calling autonomous agents and their tasks: creating a task, reading it, waiting for it to end,
//! cancelling it; running one on an instance the platform names, or assigning work to an instance
//! of your own and suspending, resuming, terminating and reading it.
//!
//! ```ignore
//! let task_id = ctx.client().autonomous_agent(Answerer).run_single_task(&answer(), "How many?")?;
//! let task = ctx.client().task(&task_id).get_as(&answer())?;
//! ```
//!
//! A task is a record of the platform's own (`ankka-task`), as an instance is (`ankka-agent-instance`);
//! these calls read and write them, and call the agent, through the same `invoke` import every call
//! uses, so each blocks the calling handler until the runtime answers it.
//!
//! An instance's notifications are not offered here: they are a stream that stays open while the
//! instance works, and a module answers every call whole. Read the task's record instead —
//! [`TaskCalls::get`], or [`TaskCalls::wait`] for a short wait.

use std::cell::Cell;
use std::collections::BTreeMap;

use serde_json::{Value, json};

use super::{Client, answer};
use crate::abi::imports::Import;
use crate::components::{AutonomousAgent, TaskType};
use crate::context::Metadata;
use crate::effects::{CommandError, ErrorCode};
use crate::proto::{self, Kind};

/// The platform's task entity.
pub const TASK_COMPONENT: &str = "ankka-task";

/// The platform's record of each autonomous agent instance, keyed `<component id>/<instance id>`.
pub const INSTANCE_COMPONENT: &str = "ankka-agent-instance";

/// How often a generated task id is tried again when it names a task that exists.
const ID_ATTEMPTS: u32 = 3;

impl Client {
    /// Creates tasks.
    pub fn tasks(&self) -> Tasks {
        Tasks {
            client: self.clone(),
        }
    }

    /// Calls about task `task_id`.
    pub fn task(&self, task_id: &str) -> TaskCalls {
        TaskCalls {
            client: self.clone(),
            task_id: task_id.to_string(),
        }
    }

    /// Calls to autonomous agent `agent`.
    pub fn autonomous_agent<C: AutonomousAgent>(&self, agent: C) -> AutonomousAgentCalls {
        let _ = agent;
        self.autonomous_agent_by_id(C::COMPONENT_ID)
    }

    /// Calls to an autonomous agent named by its id, for one this service does not declare.
    pub fn autonomous_agent_by_id(&self, component_id: &str) -> AutonomousAgentCalls {
        AutonomousAgentCalls {
            client: self.clone(),
            component_id: component_id.to_string(),
        }
    }

    /// A call whose payload and reply are JSON, as the platform's own components read them.
    fn call_json(
        &self,
        kind: Kind,
        component_id: &str,
        entity_id: &str,
        name: &str,
        body: Option<Value>,
    ) -> Result<Value, CommandError> {
        let data = body.map(|b| b.to_string().into_bytes()).unwrap_or_default();
        let request = proto::InvokeRequest {
            kind: kind as i32,
            component_id: component_id.to_string(),
            entity_id: entity_id.to_string(),
            name: name.to_string(),
            payload: Some(proto::Payload {
                content_type: "application/json".to_string(),
                manifest: String::new(),
                data,
            }),
            metadata: Some(self.metadata.to_proto()),
        };
        let reply: proto::InvokeReply = answer(Import::Invoke, request);
        match reply.result {
            Some(proto::invoke_reply::Result::Reply(reply)) => {
                let payload = reply.payload.unwrap_or_default();
                if payload.data.is_empty() || payload.content_type != "application/json" {
                    return Ok(Value::Null);
                }
                serde_json::from_slice(&payload.data).map_err(|e| {
                    CommandError::new(
                        ErrorCode::Internal,
                        format!(
                            "'{name}' of '{component_id}' answered JSON that does not parse: {e}"
                        ),
                    )
                })
            }
            Some(proto::invoke_reply::Result::Error(error)) => {
                Err(CommandError::from_proto(&error))
            }
            None => Err(CommandError::new(
                ErrorCode::Internal,
                "the runtime answered an invoke with nothing",
            )),
        }
    }

    fn task_call(
        &self,
        task_id: &str,
        name: &str,
        body: Option<Value>,
    ) -> Result<Value, CommandError> {
        self.call_json(
            Kind::EventSourcedEntity,
            TASK_COMPONENT,
            task_id,
            name,
            body,
        )
    }
}

thread_local! {
    static MINTED: Cell<u64> = const { Cell::new(0) };
}

/// An id for a task or an instance nobody named, shaped as a UUID. A module has no source of
/// randomness, so it is derived from what differs between calls — the runtime's clock and the
/// call's trace, both in its metadata — and a count of the ids this instance has made.
fn fresh_id(metadata: &Metadata) -> String {
    let n = MINTED.with(|m| {
        m.set(m.get().wrapping_add(1));
        m.get()
    });
    let mut seed = format!("{n}");
    for (k, v) in metadata.entries() {
        seed.push_str(k);
        seed.push('=');
        seed.push_str(v);
        seed.push(';');
    }
    #[cfg(not(target_arch = "wasm32"))]
    if let Ok(elapsed) = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH) {
        seed.push_str(&elapsed.as_nanos().to_string());
    }
    let high = mix(fnv(seed.as_bytes(), 0xcbf2_9ce4_8422_2325));
    let low = mix(fnv(seed.as_bytes(), 0x6c62_272e_07bb_0142) ^ high);
    let version = (high & 0xffff_ffff_ffff_0fff) | 0x4000;
    let variant = (low & 0x3fff_ffff_ffff_ffff) | 0x8000_0000_0000_0000;
    format!(
        "{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
        version >> 32,
        (version >> 16) & 0xffff,
        version & 0xffff,
        variant >> 48,
        variant & 0xffff_ffff_ffff
    )
}

fn fnv(bytes: &[u8], basis: u64) -> u64 {
    bytes.iter().fold(basis, |h, b| {
        (h ^ u64::from(*b)).wrapping_mul(0x0000_0100_0000_01b3)
    })
}

/// splitmix64's finaliser: spreads every input bit over the output.
fn mix(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    z ^ (z >> 31)
}

/// Content that travels with a task: inline, or a URI for the agent's tools to fetch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Attachment {
    name: String,
    content_type: String,
    content: Content,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Content {
    Inline(String),
    Reference(String),
}

impl Attachment {
    /// `text` carried in the task itself.
    pub fn inline(
        name: impl Into<String>,
        content_type: impl Into<String>,
        text: impl Into<String>,
    ) -> Attachment {
        Attachment {
            name: name.into(),
            content_type: content_type.into(),
            content: Content::Inline(text.into()),
        }
    }

    /// Content at `uri`, which the agent's tools fetch.
    pub fn reference(
        name: impl Into<String>,
        content_type: impl Into<String>,
        uri: impl Into<String>,
    ) -> Attachment {
        Attachment {
            name: name.into(),
            content_type: content_type.into(),
            content: Content::Reference(uri.into()),
        }
    }

    fn to_json(&self) -> Value {
        let content = match &self.content {
            Content::Inline(text) => json!({ "type": "Inline", "text": text }),
            Content::Reference(uri) => json!({ "type": "Reference", "uri": uri }),
        };
        json!({ "name": self.name, "contentType": self.content_type, "content": content })
    }
}

/// A task to create: its instructions, and optionally its id, attachments and dependencies. A
/// string is a task with instructions and nothing else.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NewTask {
    instructions: String,
    id: Option<String>,
    attachments: Vec<Attachment>,
    depends_on: Vec<String>,
}

impl NewTask {
    /// A task with `instructions`.
    pub fn new(instructions: impl Into<String>) -> NewTask {
        NewTask {
            instructions: instructions.into(),
            ..NewTask::default()
        }
    }

    /// Its id, rather than one made for it.
    pub fn id(mut self, id: impl Into<String>) -> NewTask {
        self.id = Some(id.into());
        self
    }

    /// Content that travels with it.
    pub fn attachment(mut self, attachment: Attachment) -> NewTask {
        self.attachments.push(attachment);
        self
    }

    /// Tasks whose results it needs: it is not worked until they have completed, and it is
    /// cancelled if one fails or is cancelled.
    pub fn depends_on<I, S>(mut self, task_ids: I) -> NewTask
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.depends_on.extend(task_ids.into_iter().map(Into::into));
        self
    }
}

impl From<&str> for NewTask {
    fn from(instructions: &str) -> NewTask {
        NewTask::new(instructions)
    }
}

impl From<String> for NewTask {
    fn from(instructions: String) -> NewTask {
        NewTask::new(instructions)
    }
}

/// Creates tasks: `client.tasks().create(&answer(), "How many?")` answers the new task's id.
#[derive(Debug, Clone)]
pub struct Tasks {
    client: Client,
}

impl Tasks {
    /// Creates a task of `task_type`, pending until an instance takes it. A dependency that does
    /// not exist is refused before anything is written; one that has already failed or been
    /// cancelled cancels the new task at once, naming it.
    pub fn create<R>(
        &self,
        task_type: &TaskType<R>,
        task: impl Into<NewTask>,
    ) -> Result<String, CommandError> {
        self.create_named(task_type.name(), task.into())
    }

    fn create_named(&self, type_name: &str, task: NewTask) -> Result<String, CommandError> {
        let client = &self.client;
        let mut dependencies: Vec<String> = Vec::new();
        for dep in task.depends_on {
            if !dependencies.contains(&dep) {
                dependencies.push(dep);
            }
        }
        for dep in &dependencies {
            client.task_call(dep, "get", None)?;
        }
        let body = json!({
            "typeName": type_name,
            "instructions": task.instructions,
            "attachments": task.attachments.iter().map(Attachment::to_json).collect::<Vec<_>>(),
            "dependencies": dependencies,
        });
        let task_id = match task.id {
            Some(id) => {
                client.task_call(&id, "create", Some(body))?;
                id
            }
            None => {
                let mut attempt = 1;
                loop {
                    let id = fresh_id(&client.metadata);
                    match client.task_call(&id, "create", Some(body.clone())) {
                        Ok(_) => break id,
                        Err(e) if e.code == ErrorCode::Conflict && attempt < ID_ATTEMPTS => {
                            attempt += 1;
                        }
                        Err(e) => return Err(e),
                    }
                }
            }
        };
        for dep in &dependencies {
            let added =
                client.task_call(dep, "add-dependent", Some(json!({ "taskId": task_id })))?;
            if let Some(ended) = added.get("alreadyEnded").and_then(Value::as_str) {
                let reason = format!("dependency '{dep}' {ended}");
                client.task_call(&task_id, "cancel", Some(json!({ "reason": reason })))?;
                break;
            }
        }
        Ok(task_id)
    }
}

/// A task as its record says it is. `result` is decoded as the task type's for
/// [`TaskCalls::get_as`], and is the result's JSON for [`TaskCalls::get`].
#[derive(Debug, Clone, PartialEq)]
pub struct TaskSnapshot<R> {
    /// The task's id.
    pub id: String,
    /// Its type's wire name.
    pub type_name: String,
    /// Where it is: `pending`, `assigned`, `in-progress`, `completed`, `failed`, `cancelled`…
    pub status: String,
    /// The result it completed with.
    pub result: Option<R>,
    /// Why it failed or was cancelled.
    pub reason: Option<String>,
    /// How many iterations were spent on it.
    pub iterations: u32,
    /// The agent and instance working it: `(component id, instance id)`.
    pub assignee: Option<(String, String)>,
    /// The whole record, as the platform wrote it.
    pub record: Value,
}

impl<R> TaskSnapshot<R> {
    /// Whether it has completed, failed or been cancelled: nothing more will happen to it.
    pub fn ended(&self) -> bool {
        matches!(self.status.as_str(), "completed" | "failed" | "cancelled")
    }
}

/// Calls about one task.
#[derive(Debug, Clone)]
pub struct TaskCalls {
    client: Client,
    task_id: String,
}

fn text(record: &Value, field: &str) -> Option<String> {
    record
        .get(field)
        .and_then(Value::as_str)
        .map(str::to_string)
}

impl TaskCalls {
    /// The task's id.
    pub fn id(&self) -> &str {
        &self.task_id
    }

    fn snapshot<R>(
        &self,
        expected: Option<&str>,
        decode: impl FnOnce(&str) -> Result<R, String>,
    ) -> Result<TaskSnapshot<R>, CommandError> {
        let record = self.client.task_call(&self.task_id, "get", None)?;
        let type_name = text(&record, "typeName").unwrap_or_default();
        if let Some(expected) = expected.filter(|e| *e != type_name) {
            return Err(CommandError::new(
                ErrorCode::BadRequest,
                format!(
                    "task '{}' is a '{type_name}' task, not '{expected}'",
                    self.task_id
                ),
            ));
        }
        let result = match record.get("result").and_then(Value::as_str) {
            None => None,
            Some(stored) => Some(decode(stored).map_err(|e| {
                CommandError::new(
                    ErrorCode::Internal,
                    format!(
                        "task '{}' has a result that does not decode: {e}",
                        self.task_id
                    ),
                )
            })?),
        };
        let assignee = record.get("assignee").and_then(|a| {
            Some((
                a.get("componentId")?.as_str()?.to_string(),
                a.get("instanceId")?.as_str()?.to_string(),
            ))
        });
        Ok(TaskSnapshot {
            id: text(&record, "id").unwrap_or_else(|| self.task_id.clone()),
            type_name,
            status: text(&record, "status").unwrap_or_default(),
            result,
            reason: text(&record, "reason"),
            iterations: record
                .get("iterations")
                .and_then(Value::as_u64)
                .unwrap_or(0) as u32,
            assignee,
            record,
        })
    }

    /// The task as its record says it is, its result as JSON.
    pub fn get(&self) -> Result<TaskSnapshot<Value>, CommandError> {
        self.snapshot(None, |stored| {
            serde_json::from_str(stored).map_err(|e| e.to_string())
        })
    }

    /// The task, its result decoded as `task_type`'s. A task of another type is refused.
    pub fn get_as<R>(&self, task_type: &TaskType<R>) -> Result<TaskSnapshot<R>, CommandError>
    where
        R: serde::de::DeserializeOwned + 'static,
    {
        self.snapshot(Some(task_type.name()), |stored| task_type.decode(stored))
    }

    /// Awaits the task's end by reading its record until it has completed, failed or been
    /// cancelled, at most `reads` times. A module has no clock to sleep on, so the reads follow
    /// one another as fast as the runtime answers them: this suits a task that is nearly done. A
    /// task that has not ended is refused with `Timeout`, naming where it had got to; a longer
    /// wait belongs in a workflow step retried on a timer, or with the caller.
    pub fn wait(&self, reads: u32) -> Result<TaskSnapshot<Value>, CommandError> {
        let mut last = self.get()?;
        for _ in 1..reads.max(1) {
            if last.ended() {
                return Ok(last);
            }
            last = self.get()?;
        }
        if last.ended() {
            return Ok(last);
        }
        Err(CommandError::new(
            ErrorCode::Timeout,
            format!(
                "task '{}' had not ended after {reads} reads; it is {}",
                self.task_id, last.status
            ),
        ))
    }

    /// Cancels the task: at once when it is waiting, at the end of the current iteration when an
    /// instance is working it. A task that has already ended refuses.
    pub fn cancel(&self) -> Result<(), CommandError> {
        self.cancel_because("cancelled by caller")
    }

    /// Cancels the task, recording `reason`.
    pub fn cancel_because(&self, reason: &str) -> Result<(), CommandError> {
        let client = &self.client;
        client.task_call(&self.task_id, "cancel", Some(json!({ "reason": reason })))?;
        let record = client.task_call(&self.task_id, "get", None)?;
        let assignee = record.get("assignee").and_then(|a| {
            Some((
                a.get("componentId")?.as_str()?.to_string(),
                a.get("instanceId")?.as_str()?.to_string(),
            ))
        });
        if let Some((component_id, instance_id)) = assignee {
            client.call_json(
                Kind::AutonomousAgent,
                &component_id,
                &instance_id,
                "dequeue",
                Some(json!({ "taskId": self.task_id, "reason": reason })),
            )?;
        }
        Ok(())
    }
}

/// Calls to one autonomous agent: a task run on an instance the platform names, or an instance of
/// the caller's.
#[derive(Debug, Clone)]
pub struct AutonomousAgentCalls {
    client: Client,
    component_id: String,
}

impl AutonomousAgentCalls {
    /// Creates the task, starts an instance of its own on it, and answers the task's id at once.
    pub fn run_single_task<R>(
        &self,
        task_type: &TaskType<R>,
        task: impl Into<NewTask>,
    ) -> Result<String, CommandError> {
        let client = &self.client;
        let task_id = client.tasks().create_named(task_type.name(), task.into())?;
        client.call_json(
            Kind::AutonomousAgent,
            &self.component_id,
            &fresh_id(&client.metadata),
            "run-single-task",
            Some(json!({ "taskId": task_id })),
        )?;
        Ok(task_id)
    }

    /// Calls to instance `instance_id`, which exists once something is assigned to it.
    pub fn instance(&self, instance_id: &str) -> InstanceCalls {
        InstanceCalls {
            client: self.client.clone(),
            component_id: self.component_id.clone(),
            instance_id: instance_id.to_string(),
        }
    }
}

/// What an assignment did. The task records decide: a task that was not pending, or does not
/// exist, is refused by its record and is not queued.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Assignment {
    /// The tasks queued, in order.
    pub accepted: Vec<String>,
    /// The tasks refused, each with why.
    pub refused: BTreeMap<String, CommandError>,
}

/// An instance, as its record says it is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentState {
    /// `idle`, `waiting`, `working`, `suspended` or `terminated`.
    pub phase: String,
    /// Whether it has been suspended and not resumed.
    pub suspended: bool,
    /// Whether it has been terminated; it takes no more work.
    pub terminated: bool,
    /// The task it is working.
    pub current_task: Option<String>,
    /// The iteration it is on, of the current task.
    pub iteration: u32,
    /// The tasks queued behind it, in order.
    pub queued: Vec<String>,
}

/// Calls to one instance of an autonomous agent.
#[derive(Debug, Clone)]
pub struct InstanceCalls {
    client: Client,
    component_id: String,
    instance_id: String,
}

impl InstanceCalls {
    fn op(&self, name: &str, body: Option<Value>) -> Result<Value, CommandError> {
        self.client.call_json(
            Kind::AutonomousAgent,
            &self.component_id,
            &self.instance_id,
            name,
            body,
        )
    }

    /// Queues pending tasks on this instance, in order. A terminated instance refuses with
    /// `Conflict`.
    pub fn assign<I, S>(&self, task_ids: I) -> Result<Assignment, CommandError>
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        let ids: Vec<String> = task_ids.into_iter().map(Into::into).collect();
        let answer = self.op("assign", Some(json!({ "taskIds": ids })))?;
        let accepted = answer
            .get("accepted")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default();
        let refused = answer
            .get("refused")
            .and_then(Value::as_object)
            .map(|r| {
                r.iter()
                    .map(|(id, refusal)| {
                        let code = match refusal.get("code").and_then(Value::as_str) {
                            Some("NotFound") => ErrorCode::NotFound,
                            Some("Conflict") => ErrorCode::Conflict,
                            Some("BadRequest") => ErrorCode::BadRequest,
                            Some("Forbidden") => ErrorCode::Forbidden,
                            Some("Unavailable") => ErrorCode::Unavailable,
                            Some("Unauthorized") => ErrorCode::Unauthorized,
                            Some("Timeout") => ErrorCode::Timeout,
                            _ => ErrorCode::Internal,
                        };
                        let message = refusal
                            .get("message")
                            .and_then(Value::as_str)
                            .unwrap_or_default();
                        (id.clone(), CommandError::new(code, message))
                    })
                    .collect()
            })
            .unwrap_or_default();
        Ok(Assignment { accepted, refused })
    }

    /// Stops taking up work after the current iteration, until resumed.
    pub fn suspend(&self) -> Result<(), CommandError> {
        self.op("suspend", None).map(|_| ())
    }

    /// Takes up work again.
    pub fn resume(&self) -> Result<(), CommandError> {
        self.op("resume", None).map(|_| ())
    }

    /// Stops the instance for good; its tasks go back to pending for another to take.
    pub fn terminate(&self) -> Result<(), CommandError> {
        self.op("terminate", None).map(|_| ())
    }

    /// The instance as its record says it is. One that has never been used is idle.
    pub fn state(&self) -> Result<AgentState, CommandError> {
        let record = self.client.call_json(
            Kind::EventSourcedEntity,
            INSTANCE_COMPONENT,
            &format!("{}/{}", self.component_id, self.instance_id),
            "get",
            None,
        )?;
        let flag = |name: &str| record.get(name).and_then(Value::as_bool).unwrap_or(false);
        let current = record.get("current").filter(|c| !c.is_null());
        let queued: Vec<String> = record
            .get("queue")
            .and_then(Value::as_array)
            .map(|q| {
                q.iter()
                    .filter_map(|v| v.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default();
        let (suspended, terminated) = (flag("suspended"), flag("terminated"));
        let phase = if terminated {
            "terminated"
        } else if suspended {
            "suspended"
        } else if current.is_some() {
            "working"
        } else if !queued.is_empty() {
            "waiting"
        } else {
            "idle"
        };
        Ok(AgentState {
            phase: phase.to_string(),
            suspended,
            terminated,
            current_task: current.and_then(|c| text(c, "taskId")),
            iteration: current
                .and_then(|c| c.get("iteration"))
                .and_then(Value::as_u64)
                .unwrap_or(0) as u32,
            queued,
        })
    }
}
