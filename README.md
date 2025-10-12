## P64 — K8s security posture (OPA Gatekeeper + kube-linter + kube-bench)

- Gatekeeper:
  ```bash
  gh workflow run "Install OPA Gatekeeper"
  kubectl apply -f k8s/gatekeeper/templates/
  kubectl apply -f k8s/gatekeeper/constraints/staging/
  ```

•kube-linter (PR workflow): .github/workflows/kube-linter.yml
•kube-bench (CIS as Job): kubectl apply -f k8s/kube-bench/job.yaml (одноразово)

Полезно:
•Политики запрещают :latest, privileged, hostPath, требуют probes/resources, ограничивают registry.
