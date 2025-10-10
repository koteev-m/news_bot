# P62 — Supply-chain security & K8s policies

## Cosign (keyless)
- Workflow `cosign-sign.yml` подписывает образ с OIDC GitHub.
- Проверка подписи — политика Kyverno `verify-images-cosign` (keyless: issuer `https://token.actions.githubusercontent.com`, subject `https://github.com/*/*`).

## Kyverno Policies
- `require-requests-limits` — ресурсы обязательны.
- `require-probes` — liveness/readiness обязательны.
- `disallow-privileged-and-net-raw` — запрет privileged и требование `drop NET_RAW`.
- `disallow-latest-tag` — запрет `:latest`.
- `verify-images-cosign` — верификация подписей cosign.

## Pod Security Standards
- Namespace-лейблы `restricted` для staging/prod.

## NetworkPolicies
- Default-deny; разрешаем ingress из `ingress-nginx`, egress DNS + интернет при необходимости.

## Развёртывание
```bash
# Kyverno
gh workflow run "Install Kyverno"

# Политики
kubectl apply -f k8s/kyverno/policies/
kubectl apply -f k8s/pss/namespace-labels.yaml
kubectl apply -f k8s/networkpolicies/newsbot-staging.yaml
kubectl apply -f k8s/networkpolicies/newsbot-prod.yaml
```
