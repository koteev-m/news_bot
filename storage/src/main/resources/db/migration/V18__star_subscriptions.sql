-- Star subscriptions minimal schema
create table if not exists star_subscriptions (
    id           bigserial primary key,
    user_id      bigint      not null,
    plan         text        not null,
    status       text        not null, -- ACTIVE | CANCELED | EXPIRED | TRIAL
    auto_renew   boolean     not null default false,
    renew_at     timestamptz null,
    trial_until  timestamptz null,
    created_at   timestamptz not null default now()
);

-- Индекс для выборок по due-ренью (auto_renew=true AND renew_at <= now())
create index if not exists ix_star_subscriptions_due
    on star_subscriptions (renew_at)
    where auto_renew = true;

-- Уникальность «только одна активная на пользователя»
create unique index if not exists ux_star_subscriptions_user_active
    on star_subscriptions (user_id)
    where status = 'ACTIVE';
