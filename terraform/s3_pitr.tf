# включаем lifecycle и версионирование для бэкапов (если ещё не включено)
resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    id     = "expire-old"
    status = "Enabled"
    expiration { days = 180 }
    noncurrent_version_expiration { noncurrent_days = 90 }
  }
}
