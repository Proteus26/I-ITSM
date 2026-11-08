create table users (
    id                   bigint generated always as identity primary key,
    role                 varchar(20)  not null,
    email                varchar(255) not null,
    full_name            varchar(255) not null,
    password_hash        varchar(255) not null,
    active               boolean      not null default true,
    created_at           timestamptz  not null,
    department           varchar(255),
    technician_level     varchar(20),
    max_active_incidents integer,
    available            boolean,
    constraint uq_users_email unique (email),
    constraint ck_users_role check (role in ('EMPLOYEE', 'TECHNICIAN', 'ADMIN')),
    constraint ck_users_technician_level check (technician_level in ('L1', 'L2', 'TEAM_LEAD')),
    constraint ck_users_max_active_incidents check (max_active_incidents > 0),
    constraint ck_users_technician_columns check (
        role <> 'TECHNICIAN'
        or (technician_level is not null and max_active_incidents is not null and available is not null)
    )
);

-- category_code is a plain string for now; a later migration will add an FK to categories.
create table technician_skills (
    technician_id bigint       not null references users (id) on delete cascade,
    category_code varchar(255) not null,
    primary key (technician_id, category_code)
);

create index idx_technician_skills_category_code on technician_skills (category_code);
