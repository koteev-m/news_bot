# P83 — Secrets & Key Management (Vault/KMS, Envelope Encryption, Rotation)

## Envelope Encryption
- **DEK** (data encryption key) генерируется приложением, используется для AES-GCM.
- **Wrap/Unwrap** DEK — через KMS (Vault Transit).
- В хранилище сохраняется **envelope JSON**: `kid`, `iv`, `ct`, `dekWrapped`.

## API
- `POST /api/crypto/put {tenantId, name, plaintext}` → сохраняет envelope в БД.
- `GET /api/crypto/get?tenantId=&name=` → возвращает расшифрованный plaintext.

## Ротация
- Создать новую версию ключа (`VAULT_KEY_ID_NEW`), перезашифровать хранимые значения, сменить keyId по завершении.
- См. `tools/crypto/rotate_key.sh`.

## CI
- Secrets drift — проверяет наличие чувствительных секретов (под управление админов).
