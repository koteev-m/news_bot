alter table star_subscriptions
    add constraint chk_star_subscriptions_status
        check (status in ('ACTIVE', 'CANCELED', 'EXPIRED', 'TRIAL'));

alter table star_subscriptions
    add constraint chk_star_subscriptions_plan
        check (plan is not null and length(trim(plan)) > 0);
