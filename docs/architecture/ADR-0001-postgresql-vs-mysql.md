# ADR-0001: PostgreSQL over MySQL

## Status
Accepted

## Context
The Task entity needs to store agent-generated context (repo snapshot, plan,
running notes, tool call metadata) whose shape will keep changing as the
Agent Orchestrator evolves. A fully normalized schema for this would mean a
migration every time the orchestrator starts tracking a new piece of state.

## Decision
Use PostgreSQL, storing the variable part of task state in a `JSONB` column
(`tasks.context`), with a GIN index so we can query into it
(`context->>'repoBranch' = ?`) once specific fields prove worth indexing,
without needing to promote them to real columns first.

## Why not MySQL
Both work for V1. Postgres was preferred because:
- Native JSONB with indexing (`GIN`) is more mature than MySQL's JSON type,
  which is stored as text-ish and can't be indexed directly - you'd need
  generated columns as a workaround.
- Stronger consistency guarantees under concurrent writes, which matters
  once multiple orchestrator loop iterations are writing to the same task
  row (see ADR-0002).
- Arrays and richer types (useful later for tags, tool allow-lists) are
  first-class in Postgres.

## Consequences
- Business logic must not creep into JSON queries; JSONB is for agent
  scratch state, not a replacement for normalized entities like Task itself.
- Any field in `context` that becomes something we filter/join on
  frequently should be promoted to a real column via a Flyway migration
  rather than living in JSON indefinitely.
