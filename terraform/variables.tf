variable "s3_backups_bucket" {
  type        = string
  description = "Name of the S3 bucket for DB/app backups (must be globally unique)"
}

variable "argocd_namespace" {
  type    = string
  default = "argocd"
}

variable "external_secrets_namespace" {
  type    = string
  default = "external-secrets"
}

variable "external_secrets_version" {
  type    = string
  default = "0.9.19"
}

variable "argocd_version" {
  type    = string
  default = "5.51.6"
}

variable "aws_iam_irsa_role_arn" {
  type        = string
  default     = ""
  description = "(Optional) IRSA role ARN for ESO to access Secrets Manager/SSM"
}
