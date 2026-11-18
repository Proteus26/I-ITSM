-- P3: service catalog, SLA policies and system settings. Extends the minimal tables from
-- V20261118_1100__sla_reference_tables.sql with ALTER TABLE (P4 keeps reading it_services(id, name) and sla_events).

alter table it_services
    add column code        varchar(50),
    add column description varchar(500),
    add column criticality int          not null default 2 check (criticality between 1 and 4),
    add column owner_team  varchar(100),
    add column active      boolean      not null default true;

-- Rows that already exist (none on a fresh database) get a generated code before the column becomes mandatory.
update it_services set code = 'SVC-' || id where code is null;
alter table it_services alter column code set not null;
alter table it_services add constraint uq_it_services_code unique (code);

insert into it_services (code, name, description, criticality, owner_team) values
    ('PAYMENT',  'Payment',        'Card and invoice payment processing',     4, 'Payments'),
    ('EMAIL',    'Email',          'Corporate email and calendar',            2, 'Workplace'),
    ('VPN',      'VPN',            'Remote access to the corporate network',  3, 'Network'),
    ('DATABASE', 'Database',       'Shared database clusters',                4, 'Platform'),
    ('AUTH',     'Authentication', 'Single sign-on and identity provider',    4, 'Security'),
    ('STORAGE',  'Cloud Storage',  'File storage and sharing',                2, 'Platform'),
    ('HR',       'HR Portal',      'Leave, payroll and personnel records',    1, 'Business Apps'),
    ('WEBSITE',  'Website',        'Public website and customer portal',      3, 'Web')
on conflict do nothing;

create table sla_policies (
    id                 bigint generated always as identity primary key,
    service_id         bigint      references it_services (id) on delete cascade, -- null = default policy
    priority           varchar(2)  not null check (priority in ('P1', 'P2', 'P3', 'P4')),
    response_minutes   int         not null check (response_minutes > 0),
    resolution_minutes int         not null check (resolution_minutes >= response_minutes),
    warning_threshold_pct int      not null default 75 check (warning_threshold_pct between 1 and 99),
    constraint uq_sla_policies_service_priority unique (service_id, priority)
);

-- unique (service_id, priority) does not catch duplicates when service_id is null, so the default row gets its own index.
create unique index uq_sla_policies_default_priority on sla_policies (priority) where service_id is null;

insert into sla_policies (service_id, priority, response_minutes, resolution_minutes) values
    (null, 'P1', 5, 30),
    (null, 'P2', 15, 120),
    (null, 'P3', 60, 480),
    (null, 'P4', 240, 1440);

create table system_settings (
    key   varchar(100) primary key,
    value varchar(500) not null
);

insert into system_settings (key, value) values
    ('priority.strategy', 'weighted'),
    ('assignment.strategy', 'skill-least-loaded');

-- One WARNING / RESPONSE_BREACH / RESOLUTION_BREACH per incident: the SLA monitor relies on this for idempotency.
alter table sla_events add constraint uq_sla_events_incident_type unique (incident_id, type);
