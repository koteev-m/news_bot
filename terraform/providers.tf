variable "aws_region" {
  type    = string
  default = "eu-central-1"
}

variable "kubeconfig_path" {
  type    = string
  default = "~/.kube/config"
}

variable "kube_context" {
  type    = string
  default = null
}

variable "namespace_staging" {
  type    = string
  default = "newsbot-staging"
}

variable "namespace_prod" {
  type    = string
  default = "newsbot-prod"
}

provider "aws" {
  region = var.aws_region
}

provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

provider "helm" {
  kubernetes {
    config_path    = var.kubeconfig_path
    config_context = var.kube_context
  }
}
