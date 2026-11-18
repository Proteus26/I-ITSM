-- P4: audit log and notifications.

-- No foreign keys on purpose: audit history must survive when users or incidents are deleted, and login events
-- have no incident at all.
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

-- A notification is meaningless without its recipient or its incident, so both cascade.
create table notifications (
    id           bigserial primary key,
    recipient_id bigint        not null references users (id) on delete cascade,
    incident_id  bigint        references incidents (id) on delete cascade,
    title        varchar(200)  not null,
    body         varchar(1000) not null,
    link         varchar(300),
    created_at   timestamptz   not null default now(),
    read_at      timestamptz
);

create index idx_notifications_recipient on notifications (recipient_id, read_at);
create index idx_notifications_incident  on notifications (incident_id);
