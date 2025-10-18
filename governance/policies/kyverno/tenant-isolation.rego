package tenant_isolation

deny[msg] {
  input.request.kind.kind == "Pod"
  not input.request.object.metadata.namespace
  msg := "Namespace must be explicit for multi-tenant clusters"
}

deny[msg] {
  input.request.kind.kind == "NetworkPolicy"
  not input.request.object.metadata.namespace
  msg := "NetworkPolicy must belong to tenant namespace"
}

deny[msg] {
  some ns1, ns2
  ns1 != ns2
  input.request.kind.kind == "NetworkPolicy"
  input.request.object.spec.egress[_].to[_].namespaceSelector.matchLabels.tenant == ns1
  input.request.object.spec.ingress[_].from[_].namespaceSelector.matchLabels.tenant == ns2
  msg := "Cross-tenant network traffic forbidden"
}
