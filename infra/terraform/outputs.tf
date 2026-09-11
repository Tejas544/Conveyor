output "eks_cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "eks_cluster_name" {
  value = aws_eks_cluster.this.name
}

output "ecr_repository_urls" {
  value = { for name, repo in aws_ecr_repository.images : name => repo.repository_url }
}

output "rds_endpoint" {
  value     = aws_db_instance.this.endpoint
  sensitive = true
}

output "vpc_id" {
  value = aws_vpc.this.id
}
