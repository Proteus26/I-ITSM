-- P2: categories, incidents, incident notes.

create table categories (
    code        varchar(255) primary key,
    name        varchar(100) not null,
    description varchar(500),
    active      boolean      not null default true,
    constraint ck_categories_code_upper check (code = upper(code))
);

insert into categories (code, name, description) values
    ('HARDWARE',       'Hardware',       'Laptops, desktops, printers and other devices'),
    ('SOFTWARE',       'Software',       'Installed applications and operating systems'),
    ('NETWORK',        'Network',        'Connectivity, Wi-Fi, VPN, DNS and firewalls'),
    ('DATABASE',       'Database',       'Database servers, queries and data access'),
    ('CLOUD',          'Cloud',          'Cloud platforms and hosted infrastructure'),
    ('SECURITY',       'Security',       'Suspicious activity, malware and access violations'),
    ('AUTHENTICATION', 'Authentication', 'Login, passwords, SSO and multi-factor'),
    ('APPLICATION',    'Application',    'Business applications and internal services'),
    ('STORAGE',        'Storage',        'File shares, backups and storage systems');

-- Skills recorded before categories existed: keep them as inactive categories so the foreign key can be added.
insert into categories (code, name, description, active)
select distinct category_code, left(category_code, 100), 'Imported from technician skills', false
from technician_skills
where category_code not in (select code from categories);

alter table technician_skills
    add constraint fk_technician_skills_category foreign key (category_code) references categories (code);

create sequence incident_number_seq start with 1 increment by 1;

create table incidents (
    id                 bigint generated always as identity primary key,
    number             varchar(20)   not null,
    title              varchar(200)  not null,
    description        varchar(4000) not null,
    reporter_id        bigint        not null references users (id),
    category_code      varchar(255)  references categories (code),
    -- No foreign key yet: it_services is created by the SLA/service module. Add
    -- "alter table incidents add constraint fk_incidents_service foreign key (service_id) references it_services (id)"
    -- in a later migration, once that table exists.
    service_id         bigint,
    assignee_id        bigint        references users (id),
    impact             varchar(20)   not null,
    urgency            varchar(20)   not null,
    affected_users     integer       not null default 1,
    priority           varchar(2),
    priority_score     double precision,
    status             varchar(20)   not null,
    response_due_at    timestamptz,
    resolution_due_at  timestamptz,
    acknowledged_at    timestamptz,
    resolved_at        timestamptz,
    closed_at          timestamptz,
    resolution_summary varchar(4000),
    root_cause         varchar(2000),
    feedback_rating    integer,
    feedback_comment   varchar(1000),
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    version            bigint        not null default 0,
    constraint uq_incidents_number unique (number),
    constraint ck_incidents_impact check (impact in ('INDIVIDUAL', 'TEAM', 'DEPARTMENT', 'ORGANISATION')),
    constraint ck_incidents_urgency check (urgency in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint ck_incidents_priority check (priority in ('P1', 'P2', 'P3', 'P4')),
    constraint ck_incidents_status check (status in (
        'NEW', 'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO',
        'RESOLVED', 'VERIFIED', 'CLOSED', 'CANCELLED')),
    constraint ck_incidents_affected_users check (affected_users >= 1),
    constraint ck_incidents_feedback_rating check (feedback_rating between 1 and 5)
);

create index idx_incidents_reporter on incidents (reporter_id, created_at desc);
create index idx_incidents_assignee_status on incidents (assignee_id, status);
create index idx_incidents_status on incidents (status);
create index idx_incidents_category on incidents (category_code);
create index idx_incidents_created_at on incidents (created_at);

create table incident_notes (
    id          bigint generated always as identity primary key,
    incident_id bigint        not null references incidents (id) on delete cascade,
    author_id   bigint        not null references users (id),
    type        varchar(20)   not null,
    body        varchar(4000) not null,
    created_at  timestamptz   not null,
    constraint ck_incident_notes_type check (type in ('INVESTIGATION', 'INFO_REQUEST', 'INFO_RESPONSE', 'COMMENT'))
);

create index idx_incident_notes_incident on incident_notes (incident_id, created_at);
