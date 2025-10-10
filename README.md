## P62 — Image signing & K8s policies

- Подпись образов (cosign, keyless): `.github/workflows/cosign-sign.yml`
- Kyverno политики: `k8s/kyverno/policies/*`
- PSS restricted: `k8s/pss/namespace-labels.yaml`
- NetworkPolicies: `k8s/networkpolicies/*.yaml`

Быстрый старт:
```bash
gh workflow run "Install Kyverno"
kubectl apply -f k8s/kyverno/policies/
```
