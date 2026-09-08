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
    success         INTEGER NOT NULL,
    error_message   TEXT,
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

-- 007仅追加记忆表,重复启动不能重建会话与审计历史。
CREATE TABLE IF NOT EXISTS memory_entries (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    scope           VARCHAR(16) NOT NULL CHECK (scope IN ('CORE', 'ARCHIVAL')),
    content         TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries(scope);

-- 011 仅追加定时任务状态/历史两表,时间为 UTC epoch 毫秒(INTEGER),不动旧四表。
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    task_id         TEXT PRIMARY KEY NOT NULL,
    profile_name    TEXT NOT NULL,
    cron            TEXT NOT NULL,
    zone            TEXT NOT NULL,
    message         TEXT NOT NULL,
    enabled         INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    next_run_at     INTEGER,
    last_run_at     INTEGER,
    last_status     TEXT CHECK (last_status IN ('running', 'success', 'failed', 'timeout', 'unknown')),
    run_count       INTEGER NOT NULL DEFAULT 0 CHECK (run_count >= 0)
);

CREATE TABLE IF NOT EXISTS task_executions (
    execution_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id         TEXT NOT NULL REFERENCES scheduled_tasks(task_id),
    session_id      TEXT NOT NULL,
    started_at      INTEGER NOT NULL,
    success         INTEGER CHECK (success IN (0, 1)),
    error_message   TEXT,
    duration_ms     INTEGER CHECK (duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_task_executions_task_started
    ON task_executions(task_id, started_at DESC, execution_id DESC);
