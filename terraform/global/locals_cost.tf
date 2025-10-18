variable "preferred_region" { type = string }

locals {
  # Читаем из generated файла или GitHub env
  region_primary = var.preferred_region != "" ? var.preferred_region : "eu-central-1"
  region_secondary = local.region_primary == "eu-central-1" ? "us-east-1" : "eu-central-1"
}

output "selected_regions" {
  value = {
    primary   = local.region_primary
    secondary = local.region_secondary
  }
}
