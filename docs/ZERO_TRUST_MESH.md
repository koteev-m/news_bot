# P75 — Zero-Trust Service Mesh (Istio)

## Компоненты
- mTLS STRICT (`PeerAuthentication`) для `newsbot-staging`/`newsbot-prod`
- TLS межсервисный (`DestinationRule` TLS ISTIO_MUTUAL)
- Ingress `Gateway` + `VirtualService` для HTTPS
- JWT `RequestAuthentication` + `AuthorizationPolicy` для `/api/admin/*` (claim `role=admin`)
- Rate-Limit (`EnvoyFilter` local_ratelimit) — 100 rps глобально и 50 rps для `/api/*`
- Сетевые политики авторизации: deny-all + allow внутри namespace на порт 8080

## CI
- `istio-analyze.yml` — `istioctl analyze k8s/istio` (без подключения к кластеру)

## Деплой
```bash
gh workflow run "Install Istio (control plane)"
kubectl apply -f k8s/istio/peerauthentication-strict.yaml
kubectl apply -f k8s/istio/destinationrule-newsbot.yaml
kubectl apply -f k8s/istio/gateway-virtualservice.yaml
kubectl apply -f k8s/istio/jwt-authz-admin.yaml
kubectl apply -f k8s/istio/envoyfilter-ratelimit.yaml
kubectl apply -f k8s/istio/authorization-denyall-allowlist.yaml
```

Проверьте наличие TLS-секрета newsbot-tls в namespace ingress (или перенастройте credentialName).
