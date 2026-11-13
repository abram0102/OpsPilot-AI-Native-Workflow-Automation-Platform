# The private document bucket (docs/TASKS.md T31: "private S3 with encryption/block public
# access"; docs/SECURITY_AND_TESTING.md §1: "S3 private per-tenant prefix, signed access,
# server-enforced tenant ownership").
#
# Tenant separation is by key prefix and enforced in the application — T13's object keys embed the
# organization and never the filename, and LocalFilesystemStorageServiceTest holds the
# containment check. A bucket per tenant would be a different design with a hard account limit;
# what this file is responsible for is that the bucket is not public and not readable without a
# signature.

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

resource "aws_s3_bucket" "documents" {
  bucket = "${var.name_prefix}-documents"

  tags = { Name = "${var.name_prefix}-documents" }
}

# All four, explicitly. The account-level setting may already do this, but an account-level
# setting is something somebody else can change, and docs/SECURITY_AND_TESTING.md §9's release
# checklist says "No hardcoded secrets or public S3 ACLs" — this is where that is guaranteed.
resource "aws_s3_bucket_public_access_block" "documents" {
  bucket = aws_s3_bucket.documents.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    # Cuts KMS request charges on every object read by reusing one data key per bucket-prefix.
    # Free, and S3 document reads are the hot path for the indexer.
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Versioning without expiry is a bill that only grows. Noncurrent versions exist to undo a
# mistake, which is a days-long need, not a years-long one.
resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    filter {}

    # A multipart upload that is never completed is invisible in the console and billed forever.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.documents]
}

# Deny any request that did not arrive over TLS. Bucket policies are evaluated before the
# application sees anything, so this holds even for a presigned URL somebody downgrades to http.
resource "aws_s3_bucket_policy" "require_tls" {
  bucket = aws_s3_bucket.documents.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyUnencryptedTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.documents.arn,
          "${aws_s3_bucket.documents.arn}/*",
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
    ]
  })

  # Without this the policy can be applied before the public access block, and a Deny-only policy
  # briefly coexisting with a public ACL is a window nobody should have to reason about.
  depends_on = [aws_s3_bucket_public_access_block.documents]
}
