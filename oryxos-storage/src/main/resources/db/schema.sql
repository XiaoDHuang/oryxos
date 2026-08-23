-- OryxOS core-stage schema (SQLite). Owned here — not via hibernate.ddl-auto=update.
-- Tables: sessions, tool_invocations, llm_calls (DemandAnalysis / TechnicalSolution).

CREATE TABLE IF NOT EXISTS sessions (
    session_id      TEXT PRIMARY KEY,
    profile_name    TEXT NOT NULL,
    channel         TEXT,
    user_id         TEXT,
    messages_json   TEXT,
    context_state   TEXT,
    status          TEXT NOT NULL,
    created_at      TEXT,
    last_active_at  TEXT,
    archived_at     TEXT
);

CREATE TABLE IF NOT EXISTS tool_invocations (
    invocation_id   TEXT PRIMARY KEY,
    session_id      TEXT,
    profile_name    TEXT,
    tool_name       TEXT NOT NULL,
    parameters      TEXT,
    status          TEXT NOT NULL,
    result          TEXT,
    error           TEXT,
    started_at      TEXT,
    completed_at    TEXT,
    token_cost      INTEGER
);

CREATE TABLE IF NOT EXISTS llm_calls (
    call_id             TEXT PRIMARY KEY,
    session_id          TEXT,
    provider            TEXT,
    model               TEXT,
    prompt_tokens       INTEGER,
    completion_tokens   INTEGER,
    total_tokens        INTEGER,
    latency_ms          INTEGER,
    status              TEXT,
    success             INTEGER NOT NULL,
    error_message       TEXT,
    started_at          TEXT,
    completed_at        TEXT
);
