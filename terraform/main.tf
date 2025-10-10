# ========== Namespaces ==========
resource "kubernetes_namespace" "staging" {
  metadata {
    name = var.namespace_staging
  }
}

resource "kubernetes_namespace" "prod" {
  metadata {
    name = var.namespace_prod
  }
}

# ========== S3 for backups ==========
resource "aws_s3_bucket" "backups" {
  bucket        = var.s3_backups_bucket
  force_destroy = false

  tags = {
    app     = "newsbot"
    purpose = "backups"
  }
}

resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id

  versioning_configuration {
    status = "Enabled"
  }
}

# ========== External Secrets Operator ==========
resource "kubernetes_namespace" "eso" {
  metadata {
    name = var.external_secrets_namespace
  }
}

resource "helm_release" "external_secrets" {
  name       = "external-secrets"
  repository = "https://charts.external-secrets.io"
  chart      = "external-secrets"
  version    = var.external_secrets_version
  namespace  = kubernetes_namespace.eso.metadata[0].name

  values = [yamlencode({
    installCRDs = true
  })]
}

locals {
  eso_auth = length(trim(var.aws_iam_irsa_role_arn)) > 0 ? {
    jwt = {
      serviceAccountRef = {
        name      = "external-secrets"
        namespace = var.external_secrets_namespace
      }
    }
  } : null
}

resource "kubernetes_manifest" "cluster_secret_store" {
  manifest = {
    apiVersion = "external-secrets.io/v1beta1"
    kind       = "ClusterSecretStore"
    metadata = {
      name = "aws-secretsmanager"
    }
    spec = {
      provider = {
        aws = merge({
          service = "SecretsManager"
          region  = var.aws_region
        }, local.eso_auth == null ? {} : {
          auth = local.eso_auth
        })
      }
    }
  }

  depends_on = [
    helm_release.external_secrets
  ]
}

# ========== Argo CD ==========
resource "kubernetes_namespace" "argocd" {
  metadata {
    name = var.argocd_namespace
  }
}

resource "helm_release" "argocd" {
  name       = "argocd"
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argo-cd"
  version    = var.argocd_version
  namespace  = kubernetes_namespace.argocd.metadata[0].name

  values = [yamlencode({
    server = {
      service = {
        type = "ClusterIP"
      }
    }
  })]
}

# ArgoCD Application: newsbot (staging)
resource "kubernetes_manifest" "app_staging" {
  manifest = {
    apiVersion = "argoproj.io/v1alpha1"
    kind       = "Application"
    metadata = {
      name      = "newsbot-staging"
      namespace = var.argocd_namespace
    }
    spec = {
      project = "default"
      source = {
        repoURL        = "https://github.com/ORG/REPO.git"
        targetRevision = "main"
        path           = "helm/newsbot"
        helm = {
          parameters = [
            {
              name  = "image.repository"
              value = "ghcr.io/ORG/REPO"
            },
            {
              name  = "image.tag"
              value = "rc"
            },
            {
              name  = "env.APP_PROFILE"
              value = "staging"
            }
          ]
        }
      }
      destination = {
        server    = "https://kubernetes.default.svc"
        namespace = var.namespace_staging
      }
      syncPolicy = {
        automated = {
          prune    = true
          selfHeal = true
        }
        syncOptions = ["CreateNamespace=true"]
      }
    }
  }

  depends_on = [
    helm_release.argocd
  ]
}

# ArgoCD Application: newsbot (prod)
resource "kubernetes_manifest" "app_prod" {
  manifest = {
    apiVersion = "argoproj.io/v1alpha1"
    kind       = "Application"
    metadata = {
      name      = "newsbot-prod"
      namespace = var.argocd_namespace
    }
    spec = {
      project = "default"
      source = {
        repoURL        = "https://github.com/ORG/REPO.git"
        targetRevision = "main"
        path           = "helm/newsbot"
        helm = {
          parameters = [
            {
              name  = "image.repository"
              value = "ghcr.io/ORG/REPO"
            },
            {
              name  = "image.tag"
              value = "latest"
            },
            {
              name  = "env.APP_PROFILE"
              value = "prod"
            }
          ]
        }
      }
      destination = {
        server    = "https://kubernetes.default.svc"
        namespace = var.namespace_prod
      }
      syncPolicy = {
        automated = {
          prune    = true
          selfHeal = true
        }
        syncOptions = ["CreateNamespace=true"]
      }
    }
  }

  depends_on = [
    helm_release.argocd
  ]
}
