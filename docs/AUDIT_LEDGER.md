# Audit Ledger (P84)

The audit ledger records every security-sensitive action performed by users, services, or automated systems. Each entry is immutable and chained together with SHA-256 hashes so that tampering can be detected.

## Events captured

The service logs HTTP interactions through the `AuditPlugin` for:

- Authenticated users identified via JWT `sub` claims.
- Internal services identified by `X-Service-Name` or `X-Client-Id` headers.
- System automations using `X-System-Actor` headers or implicit context.

Every log entry includes:

| Field | Description |
| --- | --- |
| `seq_id` | Monotonic identifier assigned by the database. |
| `ts` | UTC timestamp of the event. |
| `actor_type` | `user`, `service`, or `system`. |
| `actor_id` | Optional identifier of the actor (user id, client id, etc.). |
| `tenant_id` | Tenant scope for multi-tenant operations. |
| `action` | Canonical action string (e.g. `HTTP POST 2xx`). |
| `resource` | Target resource such as the request URI. |
| `meta_json` | Non-PII metadata like requestId, traceId, status codes. |
| `prev_hash` | Hash of the previous ledger entry (or `GENESIS`). |
| `hash` | SHA-256 of the canonical payload for this entry. |

### Hash construction

For entry `n`, the canonical payload is the pipe-separated concatenation:

```
seq_id | ts | actor_type | actor_id | tenant_id | action | resource | canonical(meta_json) | prev_hash
```

- `ts` is the ISO-8601 instant (UTC) recorded at ingestion time.
- `canonical(meta_json)` is the JSON document with keys sorted recursively and compact encoding.
- The resulting string is hashed with SHA-256 to produce `hash`.

Any modification to historical rows will break the chain because the computed hash will no longer match.

## Daily checkpoints

The `audit_checkpoints` table stores daily snapshots of the ledger to simplify attestations. Each row records:

- `day` – UTC date of the snapshot.
- `last_seq_id` – Sequence identifier of the last ledger entry for the day.
- `root_hash` – Hash of that ledger entry (effectively the head of the chain).
- `signature` – Detached signature generated with Cosign or GPG.
- `created_at` – Timestamp when the checkpoint was stored.

The signing workflow (`audit-daily.yml`) runs every day. It fetches the latest entry for the previous UTC day, signs the root hash with Cosign (keyless by default), persists the checkpoint in PostgreSQL, and stores a JSON artifact (`checkpoint.json`).

## Runbooks

### Generate a checkpoint manually

```bash
export DATABASE_URL=postgres://...
export AUDIT_SIGNER=cosign   # or gpg
export COSIGN_EXPERIMENTAL=1 # for keyless Cosign

./tools/audit/ledger_sign_daily.sh 2024-05-12
```

This produces `checkpoint.json` and upserts the checkpoint row in the database.

### Verify the ledger

```bash
export DATABASE_URL=postgres://...

./tools/audit/ledger_verify.sh 2024-05-12
```

The script recomputes each hash, validates the `prev_hash` chain, and checks that the final hash matches the checkpoint for the given day. It prints the checkpoint signature to aid external verification.

To verify a custom range:

```bash
./tools/audit/ledger_verify.sh 2024-05-12 4200
```

## Operational guidance

- Install the audit layer via `installAuditLayer()` in the application bootstrap. This wires the repository, service, plugin, and API routes.
- `AuditPlugin` attaches to every HTTP request to log actors, tenant scope, status buckets, and trace metadata.
- `/api/audit/ledger/last` returns the latest ledger entry for quick health checks.
- `/api/audit/checkpoint/{day}` retrieves stored checkpoints for compliance attestations.

## Constraints

- Only non-PII metadata should be added to `meta_json`. Avoid personal data, secrets, or access tokens.
- Ledger operations run synchronously in the same transaction as the request logging. Keep metadata payloads compact to avoid latency impact.
- Checkpoint signatures rely on external keys (Cosign keyless via OIDC or GPG). Maintain credential rotation policies separately.
- The verification script depends on database access. Provide read-only credentials dedicated to audit verification.
