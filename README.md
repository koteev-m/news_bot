## P75 — Zero-Trust Service Mesh (Istio)

Файлы:
- `k8s/istio/peerauthentication-strict.yaml` — STRICT mTLS
- `k8s/istio/destinationrule-newsbot.yaml` — TLS ISTIO_MUTUAL
- `k8s/istio/gateway-virtualservice.yaml` — HTTPS ingress
- `k8s/istio/jwt-authz-admin.yaml` — JWT authz для `/api/admin/*`
- `k8s/istio/envoyfilter-ratelimit.yaml` — rate-limit
- `k8s/istio/authorization-denyall-allowlist.yaml` — deny-all + allow внутри ns

CI:
- `.github/workflows/istio-analyze.yml`
- `.github/workflows/istio-install.yml`

Быстрый старт:
```bash
gh workflow run "Install Istio (control plane)"
kubectl apply -f k8s/istio/
```
