## P83 — Vault/KMS Envelope Encryption + Rotation

- Core: `crypto/EnvelopeCrypto.kt`
- KMS Adapter: `integrations/kms/VaultKmsAdapter.kt`
- Storage: `repo/SecretStore.kt`
- API: `crypto/CryptoRoutes.kt`
- Rotation helper: `tools/crypto/rotate_key.sh`
- CI: `.github/workflows/secrets-drift.yml`

ENV (пример):
```env
VAULT_TRANSIT_BASE=https://vault.example/v1/transit
VAULT_TOKEN=***
VAULT_KEY_ID=newsbot-app-key
```
