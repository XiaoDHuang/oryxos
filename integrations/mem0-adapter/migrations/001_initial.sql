CREATE SCHEMA oryx_memory;
COMMENT ON SCHEMA oryx_memory IS '__ORYX_SCHEMA_MARKER__';

CREATE TABLE oryx_memory.memory_namespaces (
    workspace_id UUID NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_memory_namespaces PRIMARY KEY (workspace_id),
    CONSTRAINT ck_memory_namespaces_workspace CHECK (workspace_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_memory_namespaces_revision CHECK (revision >= 0)
);

CREATE TABLE oryx_memory.memory_operations (
    workspace_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    kind TEXT NOT NULL,
    scope TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    raw_input TEXT NOT NULL,
    state TEXT NOT NULL,
    baseline_revision BIGINT,
    committed_revision BIGINT,
    owner_token UUID,
    deadline_at TIMESTAMPTZ NOT NULL,
    result_json JSONB,
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_memory_operations PRIMARY KEY (workspace_id, operation_id),
    CONSTRAINT fk_memory_operations_namespace FOREIGN KEY (workspace_id)
        REFERENCES oryx_memory.memory_namespaces(workspace_id) ON DELETE RESTRICT,
    CONSTRAINT ck_memory_operations_workspace CHECK (workspace_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_memory_operations_id CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_memory_operations_kind CHECK (kind IN ('SAVE', 'RECALL')),
    CONSTRAINT ck_memory_operations_scope CHECK (scope IN ('CORE', 'ARCHIVAL')),
    CONSTRAINT ck_memory_operations_recall_scope CHECK (kind <> 'RECALL' OR scope = 'ARCHIVAL'),
    CONSTRAINT ck_memory_operations_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_memory_operations_raw CHECK (octet_length(raw_input) BETWEEN 1 AND 32768 AND btrim(raw_input) <> ''),
    CONSTRAINT ck_memory_operations_state CHECK (state IN ('RECEIVED', 'RUNNING', 'COMMITTED', 'FAILED', 'ABORTED')),
    CONSTRAINT ck_memory_operations_baseline CHECK (baseline_revision IS NULL OR baseline_revision >= 0),
    CONSTRAINT ck_memory_operations_committed CHECK (committed_revision IS NULL OR committed_revision >= 0),
    CONSTRAINT ck_memory_operations_owner CHECK (
        owner_token IS NULL OR owner_token <> '00000000-0000-0000-0000-000000000000'::uuid
    ),
    CONSTRAINT ck_memory_operations_result CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    CONSTRAINT ck_memory_operations_terminal CHECK (
        (state IN ('RECEIVED', 'RUNNING') AND completed_at IS NULL AND committed_revision IS NULL)
        OR (state = 'COMMITTED' AND completed_at IS NOT NULL AND committed_revision IS NOT NULL
            AND result_json IS NOT NULL AND error_code IS NULL)
        OR (state IN ('FAILED', 'ABORTED') AND completed_at IS NOT NULL
            AND result_json IS NOT NULL AND error_code IS NOT NULL)
    )
);

CREATE TABLE oryx_memory.memory_versions (
    version_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    memory_id UUID NOT NULL,
    scope TEXT NOT NULL,
    operation_id UUID NOT NULL,
    action_index INTEGER NOT NULL,
    revision BIGINT NOT NULL,
    created_revision BIGINT NOT NULL,
    event TEXT NOT NULL,
    old_content TEXT,
    new_content TEXT,
    previous_version_id UUID,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_memory_versions PRIMARY KEY (version_id),
    CONSTRAINT uq_memory_versions_action UNIQUE (workspace_id, operation_id, action_index),
    CONSTRAINT uq_memory_versions_identity UNIQUE (workspace_id, memory_id, version_id),
    CONSTRAINT fk_memory_versions_operation FOREIGN KEY (workspace_id, operation_id)
        REFERENCES oryx_memory.memory_operations(workspace_id, operation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_memory_versions_previous FOREIGN KEY (workspace_id, memory_id, previous_version_id)
        REFERENCES oryx_memory.memory_versions(workspace_id, memory_id, version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_memory_versions_ids CHECK (
        version_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND memory_id <> '00000000-0000-0000-0000-000000000000'::uuid
    ),
    CONSTRAINT ck_memory_versions_scope CHECK (scope IN ('CORE', 'ARCHIVAL')),
    CONSTRAINT ck_memory_versions_action CHECK (action_index BETWEEN 0 AND 127),
    CONSTRAINT ck_memory_versions_revision CHECK (revision >= 1 AND created_revision BETWEEN 1 AND revision),
    CONSTRAINT ck_memory_versions_event CHECK (event IN ('ADD', 'UPDATE', 'DELETE')),
    CONSTRAINT ck_memory_versions_core CHECK (scope <> 'CORE' OR event = 'ADD'),
    CONSTRAINT ck_memory_versions_content CHECK (
        (old_content IS NULL OR (octet_length(old_content) BETWEEN 1 AND 32768 AND btrim(old_content) <> ''))
        AND (new_content IS NULL OR (octet_length(new_content) BETWEEN 1 AND 32768 AND btrim(new_content) <> ''))
    ),
    CONSTRAINT ck_memory_versions_shape CHECK (
        (event = 'ADD' AND old_content IS NULL AND new_content IS NOT NULL AND previous_version_id IS NULL)
        OR (event = 'UPDATE' AND old_content IS NOT NULL AND new_content IS NOT NULL AND previous_version_id IS NOT NULL)
        OR (event = 'DELETE' AND old_content IS NOT NULL AND new_content IS NULL AND previous_version_id IS NOT NULL)
    )
);

CREATE TABLE oryx_memory.memory_current (
    workspace_id UUID NOT NULL,
    memory_id UUID NOT NULL,
    scope TEXT NOT NULL,
    content TEXT NOT NULL,
    version_id UUID NOT NULL,
    created_revision BIGINT NOT NULL,
    updated_revision BIGINT NOT NULL,
    embedding vector(__ORYX_VECTOR_DIMENSIONS__),
    embedding_model TEXT,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_memory_current PRIMARY KEY (workspace_id, memory_id),
    CONSTRAINT fk_memory_current_version FOREIGN KEY (workspace_id, memory_id, version_id)
        REFERENCES oryx_memory.memory_versions(workspace_id, memory_id, version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_memory_current_ids CHECK (memory_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_memory_current_scope CHECK (scope IN ('CORE', 'ARCHIVAL')),
    CONSTRAINT ck_memory_current_content CHECK (octet_length(content) BETWEEN 1 AND 32768 AND btrim(content) <> ''),
    CONSTRAINT ck_memory_current_revision CHECK (
        created_revision >= 1 AND updated_revision >= created_revision
    ),
    CONSTRAINT ck_memory_current_embedding CHECK (
        (scope = 'CORE' AND embedding IS NULL AND embedding_model IS NULL)
        OR (scope = 'ARCHIVAL' AND embedding IS NOT NULL AND embedding_model IS NOT NULL
            AND btrim(embedding_model) <> '')
    )
);

CREATE TABLE oryx_memory.memory_call_audits (
    call_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    call_index INTEGER NOT NULL,
    kind TEXT NOT NULL,
    phase TEXT NOT NULL,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    state TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    latency_ms BIGINT,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    total_tokens BIGINT,
    request_json JSONB,
    response_json JSONB,
    error_code TEXT,
    CONSTRAINT pk_memory_call_audits PRIMARY KEY (call_id),
    CONSTRAINT uq_memory_call_audits_index UNIQUE (workspace_id, operation_id, call_index),
    CONSTRAINT fk_memory_call_audits_operation FOREIGN KEY (workspace_id, operation_id)
        REFERENCES oryx_memory.memory_operations(workspace_id, operation_id) ON DELETE RESTRICT,
    CONSTRAINT ck_memory_call_audits_ids CHECK (call_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT ck_memory_call_audits_index CHECK (call_index >= 0),
    CONSTRAINT ck_memory_call_audits_kind CHECK (kind IN ('LLM', 'EMBEDDING')),
    CONSTRAINT ck_memory_call_audits_text CHECK (
        btrim(phase) <> '' AND btrim(provider) <> '' AND btrim(model) <> ''
    ),
    CONSTRAINT ck_memory_call_audits_state CHECK (state IN ('STARTED', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_memory_call_audits_latency CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_memory_call_audits_tokens CHECK (
        (prompt_tokens IS NULL OR prompt_tokens >= 0)
        AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (total_tokens IS NULL OR total_tokens >= 0)
        AND (prompt_tokens IS NULL OR completion_tokens IS NULL OR total_tokens IS NULL
            OR total_tokens = prompt_tokens + completion_tokens)
    ),
    CONSTRAINT ck_memory_call_audits_json CHECK (
        (request_json IS NULL OR jsonb_typeof(request_json) IN ('object', 'array'))
        AND (response_json IS NULL OR jsonb_typeof(response_json) IN ('object', 'array'))
    )
);

CREATE INDEX idx_memory_operations_state_deadline
    ON oryx_memory.memory_operations(workspace_id, state, deadline_at);
CREATE INDEX idx_memory_versions_workspace_memory_revision
    ON oryx_memory.memory_versions(workspace_id, memory_id, revision DESC, action_index DESC);
CREATE INDEX idx_memory_versions_workspace_scope_revision
    ON oryx_memory.memory_versions(workspace_id, scope, revision, action_index, memory_id);
CREATE INDEX idx_memory_current_workspace_scope_created
    ON oryx_memory.memory_current(workspace_id, scope, created_revision, memory_id);
CREATE INDEX idx_memory_current_workspace_scope_updated
    ON oryx_memory.memory_current(workspace_id, scope, updated_revision, memory_id);
CREATE INDEX idx_memory_call_audits_operation
    ON oryx_memory.memory_call_audits(workspace_id, operation_id, call_index);
CREATE INDEX idx_memory_call_audits_state
    ON oryx_memory.memory_call_audits(workspace_id, state, started_at);

CREATE FUNCTION oryx_memory.reject_memory_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '记忆历史禁止更新或删除' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_memory_versions_immutable
BEFORE UPDATE OR DELETE ON oryx_memory.memory_versions
FOR EACH ROW EXECUTE FUNCTION oryx_memory.reject_memory_version_mutation();

REVOKE ALL ON SCHEMA oryx_memory FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA oryx_memory FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA oryx_memory FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA oryx_memory REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA oryx_memory REVOKE ALL ON FUNCTIONS FROM PUBLIC;
