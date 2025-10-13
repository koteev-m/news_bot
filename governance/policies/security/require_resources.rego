package policy.security.require_resources

deny[msg] {
  c := input.spec.template.spec.containers[_]
  not c.resources.requests.cpu
  msg := sprintf("container %s missing requests.cpu", [c.name])
}

deny[msg] {
  c := input.spec.template.spec.containers[_]
  not c.resources.limits.memory
  msg := sprintf("container %s missing limits.memory", [c.name])
}
