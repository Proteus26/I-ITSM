-- P4: audit log and notifications.
-- RENAME this file so its version number fits after P1/P2/P3's migrations (e.g. V7__...).

create table audit_log (
    id           bigserial primary key,
    occurred_at  timestamptz   not null,
    actor_id     bigint,
    action       varchar(64)   not null,
    incident_id  bigint,
    from_state   varchar(32),
    to_state     varchar(32),
    description  varchar(1000) not null,
    details      jsonb         not null default '{}'::jsonb
);

create index idx_audit_incident on audit_log (incident_id, occurred_at);
create index idx_audit_actor    on audit_log (actor_id);
create index idx_audit_action   on audit_log (action);
create index idx_audit_time     on audit_log (occurred_at);

create table notifications (
    id           bigserial primary key,
    recipient_id bigint        not null,
    incident_id  bigint,
    title        varchar(200)  not null,
    body         varchar(1000) not null,
    link         varchar(300),
    created_at   timestamptz   not null default now(),
    read_at      timestamptz
);

create index idx_notifications_recipient on notifications (recipient_id, read_at);
