//! What a view or consumer that reads a topic declares about it: where it starts, and its version.

use std::time::{SystemTime, UNIX_EPOCH};

use crate::components::Source;
use crate::proto;

/// Where a topic source begins, the first time its consumer group reads a partition.
///
/// Applied once and committed at once, so a restart, a rebalance or a new instance resumes where
/// the group got to, never from here again.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StartFrom {
    /// The oldest message the broker still holds.
    Earliest,
    /// After the newest: only what is published from then on.
    Latest,
    /// The first message published at or after this time, in milliseconds since the epoch. A
    /// component is declared before any handler runs, so a start time is stated, not read.
    AtMillis(i64),
}

impl StartFrom {
    /// The first message published at or after `time`.
    pub fn at(time: SystemTime) -> StartFrom {
        let millis = match time.duration_since(UNIX_EPOCH) {
            Ok(after) => i64::try_from(after.as_millis()).unwrap_or(i64::MAX),
            Err(before) => -i64::try_from(before.duration().as_millis()).unwrap_or(i64::MAX),
        };
        StartFrom::AtMillis(millis)
    }

    pub(crate) fn to_proto(self) -> proto::StartFrom {
        let position = match self {
            StartFrom::Earliest => {
                proto::start_from::Position::Named(proto::start_from::Named::Earliest as i32)
            }
            StartFrom::Latest => {
                proto::start_from::Position::Named(proto::start_from::Named::Latest as i32)
            }
            StartFrom::AtMillis(millis) => proto::start_from::Position::AtMillis(millis),
        };
        proto::StartFrom {
            position: Some(position),
        }
    }
}

/// The first protocol in which a module can declare a start position or a version.
pub(crate) const START_POSITION_PROTOCOL: (u32, u32) = (1, 7);

/// Whether a host speaking `protocol_version` would ignore a start position and a version.
pub(crate) fn older_than_start_positions(protocol_version: &str) -> bool {
    older_than(protocol_version, START_POSITION_PROTOCOL)
}

/// The first protocol in which a module can declare a view's queries, a keyed view, or a version
/// on a view that reads an entity.
pub(crate) const DECLARED_QUERY_PROTOCOL: (u32, u32) = (1, 13);

/// Whether a host speaking `protocol_version` would not know a view's declared queries, a keyed
/// view, or a version on a view that reads an entity.
pub(crate) fn older_than_declared_queries(protocol_version: &str) -> bool {
    older_than(protocol_version, DECLARED_QUERY_PROTOCOL)
}

/// The first protocol in which a module can state a topic's contract, broker or parallel reading,
/// or a publication with a contract and a broker.
pub(crate) const CONTRACT_PROTOCOL: (u32, u32) = (1, 14);

/// Whether a host speaking `protocol_version` would ignore a contract, a broker, parallel
/// reading or a publication's contract.
pub(crate) fn older_than_workflow_sources(protocol_version: &str) -> bool {
    older_than(protocol_version, (1, 15))
}

/// Whether a view, keyed view or consumer declares a workflow as a source.
pub(crate) fn reads_workflow(component: &proto::Component) -> bool {
    let sources: Vec<&proto::Source> = match &component.detail {
        Some(proto::component::Detail::View(v)) => {
            v.source.iter().chain(v.sources.iter()).collect()
        }
        Some(proto::component::Detail::Consumer(c)) => c.source.iter().collect(),
        _ => Vec::new(),
    };
    sources.iter().any(|s| {
        matches!(&s.source, Some(proto::source::Source::Component(c)) if c.kind == proto::Kind::Workflow as i32)
    })
}

pub(crate) fn older_than_contracts(protocol_version: &str) -> bool {
    older_than(protocol_version, CONTRACT_PROTOCOL)
}

/// Whether a discovered component states what a host older than 1.14 would not know.
pub(crate) fn declares_contracts(component: &proto::Component) -> bool {
    let states =
        |s: &proto::Source| s.contract.is_some() || s.broker.is_some() || s.parallel.is_some();
    match &component.detail {
        Some(proto::component::Detail::View(d)) => {
            d.source.as_ref().is_some_and(states) || d.sources.iter().any(states)
        }
        Some(proto::component::Detail::Consumer(d)) => {
            d.source.as_ref().is_some_and(states)
                || d.produces
                    .as_ref()
                    .is_some_and(|p| p.contract.is_some() || p.broker.is_some())
        }
        _ => false,
    }
}

fn older_than(protocol_version: &str, first: (u32, u32)) -> bool {
    let mut parts = protocol_version.split('.').map(str::parse::<u32>);
    match (parts.next(), parts.next()) {
        (Some(Ok(major)), Some(Ok(minor))) => (major, minor) < first,
        _ => false,
    }
}

/// Whether a discovered component declares what a host older than 1.13 would not know: a query, a
/// keyed view's sources, or a version on a view that reads an entity.
pub(crate) fn declares_queries(component: &proto::Component) -> bool {
    let Some(proto::component::Detail::View(d)) = &component.detail else {
        return false;
    };
    let over_entity = d
        .source
        .as_ref()
        .is_some_and(|s| matches!(s.source, Some(proto::source::Source::Component(_))));
    !d.declared_queries.is_empty() || !d.sources.is_empty() || (d.version.is_some() && over_entity)
}

/// The source as discovery says it, with the start position where it is declared.
pub(crate) fn source_proto(source: &Source, start: Option<StartFrom>) -> proto::Source {
    let mut declared = source.to_proto();
    declared.start_from = start.map(StartFrom::to_proto);
    declared
}

/// What is wrong with what a view or consumer declares about a topic, naming it.
pub(crate) fn problems(
    named: &str,
    source: &Source,
    start: Option<StartFrom>,
    version: Option<u32>,
    consumer: bool,
) -> Vec<String> {
    let mut found = Vec::new();
    let topic = match source {
        Source::Topic(t) => Some(t.topic.as_str()),
        Source::Component(..) => None,
    };
    if start.is_some() && topic.is_none() {
        found.push(format!(
            "{named} declares a start position, which applies to a topic; it reads a component"
        ));
    }
    if let (true, Some(topic), None) = (consumer, topic, start) {
        found.push(format!(
            "{named} reads topic '{topic}' and declares no start position; declare \
             StartFrom::Earliest, StartFrom::Latest or a time in start_from()"
        ));
    }
    match version {
        Some(0) => found.push(format!(
            "{named} declares version 0; a version is a whole number of 1 or more"
        )),
        // A view that reads an entity is rebuilt from its journal when its version is raised; a
        // consumer has no table to rebuild, and a version there would do nothing.
        Some(_) if topic.is_none() && consumer => found.push(format!(
            "{named} declares a version, which applies to a topic; it reads a component"
        )),
        _ => {}
    }
    found
}

/// Whether a discovered component says something a host older than 1.7 would ignore.
pub(crate) fn declares_any(component: &proto::Component) -> bool {
    match &component.detail {
        Some(proto::component::Detail::View(d)) => {
            d.version.is_some() || d.source.as_ref().is_some_and(|s| s.start_from.is_some())
        }
        Some(proto::component::Detail::Consumer(d)) => {
            d.version.is_some() || d.source.as_ref().is_some_and(|s| s.start_from.is_some())
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_host_before_1_7_is_older_and_one_at_or_after_is_not() {
        assert!(older_than_start_positions("1.6"));
        assert!(older_than_start_positions("1.0"));
        assert!(!older_than_start_positions("1.7"));
        assert!(!older_than_start_positions("2.0"));
        assert!(!older_than_start_positions(""));
    }

    #[test]
    fn a_time_is_milliseconds_since_the_epoch() {
        let time = UNIX_EPOCH + std::time::Duration::from_millis(1_759_320_000_000);
        assert_eq!(StartFrom::at(time), StartFrom::AtMillis(1_759_320_000_000));
    }
}
