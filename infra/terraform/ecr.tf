# ARCHITECTURE.md §15.4's ECR line — the fallback path's registry, mirroring the $0 path's GHCR
# one-for-one (same six image names). "lifecycle policy keeps 10 images" is this table's own words.

resource "aws_ecr_repository" "images" {
  for_each = toset(var.ecr_repository_names)

  name                 = each.key
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "images" {
  for_each = aws_ecr_repository.images

  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep only the most recent ${var.ecr_image_retention_count} images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.ecr_image_retention_count
      }
      action = { type = "expire" }
    }]
  })
}
