package policy.security.no_latest_image

deny[msg] {
  img := input.spec.template.spec.containers[_].image
  endswith(img, ":latest")
  msg := sprintf("disallow :latest tag (%s)", [img])
}
