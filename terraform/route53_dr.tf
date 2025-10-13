variable "zone_id"      { type = string }
variable "record_name"  { type = string }
variable "primary_dns"  { type = string }   # lb/ingress адрес в регионе A
variable "secondary_dns"{ type = string }   # lb/ingress адрес в регионе B

resource "aws_route53_health_check" "primary_http" {
  fqdn              = var.record_name
  type              = "HTTPS"
  port              = 443
  resource_path     = "/healthz"
  failure_threshold = 3
  request_interval  = 30
}

resource "aws_route53_record" "app_failover_primary" {
  zone_id = var.zone_id
  name    = var.record_name
  type    = "CNAME"
  set_identifier = "primary"
  failover_routing_policy { type = "PRIMARY" }
  health_check_id = aws_route53_health_check.primary_http.id
  records = [var.primary_dns]
  ttl     = 30
}

resource "aws_route53_record" "app_failover_secondary" {
  zone_id = var.zone_id
  name    = var.record_name
  type    = "CNAME"
  set_identifier = "secondary"
  failover_routing_policy { type = "SECONDARY" }
  records = [var.secondary_dns]
  ttl     = 30
}
