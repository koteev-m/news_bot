# Rotation runbook / План ротации секретов

## JWT secrets / JWT секреты
1. **T+0 — prepare secondary:**
   - Ensure the current primary secret lives in `deploy/compose/.env` as `JWT_SECRET_PRIMARY`.
   - Run `bash tools/security/rotate_jwt_secret.sh "<new_primary>"` (set `ENV_FILE`/`COMPOSE_FILE` when using non-default paths).
   - The script moves the previous primary into `JWT_SECRET_SECONDARY`, writes the new primary, and redeploys the stack with `docker compose`.
2. **T+0…T+24h:** monitor error rates and authentication logs. Both old and new tokens stay valid.
3. **T+24–48h — finalize:** remove `JWT_SECRET_SECONDARY` from the environment file once all outstanding sessions expire, then reload the stack again. Document the completion in the ops journal.

## Telegram webhook secret / Секрет вебхука Telegram
1. Export the required environment variables (no secrets in shell history):
   ```bash
   export TELEGRAM_BOT_TOKEN="<bot_token>"
   export TELEGRAM_WEBHOOK_URL="https://example.com/telegram/webhook"
   ```
2. Rotate the secret and redeploy:
   ```bash
   bash tools/security/rotate_webhook_secret.sh "<new_secret>"
   ```
   The script updates `deploy/compose/.env`, calls `setWebhook` with the new secret token, and restarts the stack.
3. Validate the response: the script fails on non-2xx HTTP codes from `setWebhook`. Review Telegram API logs if an error occurs.

## Post-rotation checklist / Чек-лист после ротации
- `/metrics` responds with `200 OK`.
- `/health/db` responds with `200 OK`.
- Trigger a minimal webhook delivery (Telegram test message) and confirm 2xx response from the application logs.
- Run API smoke (`P05`) including JWT-authenticated call to ensure the new tokens are accepted.
