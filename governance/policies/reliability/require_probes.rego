package policy.reliability.require_probes

deny[msg] {
  c := input.spec.template.spec.containers[_]
  not c.readinessProbe
  msg := sprintf("readinessProbe required for %s", [c.name])
}
