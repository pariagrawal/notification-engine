-- Core schema for the notification engine.

create table notification_template (
    id               bigserial     primary key,
    code             varchar(100)  not null,
    channel          varchar(16)   not null,
    locale           varchar(16)   not null default 'en',
    subject_template varchar(500),
    body_template    text          not null,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    constraint uq_template_code_channel_locale unique (code, channel, locale)
);

create table user_preference (
    id                bigserial    primary key,
    user_id           varchar(64)  not null,
    channel           varchar(16)  not null,
    enabled           boolean      not null default true,
    destination       varchar(320),
    locale            varchar(16)  not null default 'en',
    time_zone         varchar(64)  not null default 'UTC',
    quiet_hours_start integer,
    quiet_hours_end   integer,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    constraint uq_preference_user_channel unique (user_id, channel),
    constraint ck_quiet_hours_start check (quiet_hours_start is null or quiet_hours_start between 0 and 23),
    constraint ck_quiet_hours_end check (quiet_hours_end is null or quiet_hours_end between 0 and 23)
);

create table notification (
    id              uuid         primary key,
    user_id         varchar(64)  not null,
    channel         varchar(16)  not null,
    template_code   varchar(100),
    idempotency_key varchar(128) not null,
    recipient       varchar(320),
    subject         varchar(500),
    body            text,
    payload         text,
    status          varchar(24)  not null,
    attempts        integer      not null default 0,
    failure_reason  text,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    sent_at         timestamptz,
    version         bigint       not null default 0,
    -- The durable backstop behind the Ignite idempotency cache: even if the cache is
    -- wiped, a replayed request cannot create a second notification.
    constraint uq_notification_idempotency_key unique (idempotency_key)
);

create index ix_notification_user_created on notification (user_id, created_at desc);
create index ix_notification_status on notification (status);

create table outbox_event (
    id           uuid         primary key,
    aggregate_id uuid         not null,
    event_type   varchar(64)  not null,
    topic        varchar(128) not null,
    message_key  varchar(128) not null,
    payload      text         not null,
    status       varchar(16)  not null default 'PENDING',
    attempts     integer      not null default 0,
    last_error   text,
    created_at   timestamptz  not null default now(),
    published_at timestamptz
);

-- Supports the poller's "oldest pending first" claim query.
create index ix_outbox_status_created on outbox_event (status, created_at);

create table delivery_attempt (
    id                  bigserial    primary key,
    notification_id     uuid         not null references notification (id) on delete cascade,
    attempt_no          integer      not null,
    outcome             varchar(20)  not null,
    provider            varchar(64),
    provider_message_id varchar(128),
    error               text,
    latency_ms          bigint,
    created_at          timestamptz  not null default now()
);

create index ix_delivery_attempt_notification on delivery_attempt (notification_id, attempt_no);
