package policy.compliance.no_pii_logging

violation(msg) {
  some line
  line := input.lines[_]
  re_match("(?i)(email|token|apikey|api_key|password|\\b\\d{13,16}\\b)", line)
  msg := sprintf("PII/secret pattern in log/config: %s", [line])
}
