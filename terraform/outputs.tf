output "s3_backups_bucket" {
  value = aws_s3_bucket.backups.bucket
}

output "argocd_namespace" {
  value = kubernetes_namespace.argocd.metadata[0].name
}
