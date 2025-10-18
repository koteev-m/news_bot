resource "aws_route53_health_check" "primary_https" {
  fqdn               = var.hostname
  port               = 443
  type               = "HTTPS"
  resource_path      = "/healthz"
  failure_threshold  = 3
  request_interval   = 30
  regions            = ["eu-west-1","us-east-1","ap-south-1"]
  invert_healthcheck = false
}

resource "aws_route53_record" "app_primary" {
  zone_id           = var.zone_id
  name              = var.hostname
  type              = "CNAME"
  set_identifier    = "primary"
  failover_routing_policy { type = "PRIMARY" }
  health_check_id   = aws_route53_health_check.primary_https.id
  records           = [var.primary_lb]
  ttl               = 30
}

resource "aws_route53_record" "app_secondary" {
  zone_id        = var.zone_id
  name           = var.hostname
  type           = "CNAME"
  set_identifier = "secondary"
  failover_routing_policy { type = "SECONDARY" }
  records        = [var.secondary_lb]
  ttl            = 30
}
