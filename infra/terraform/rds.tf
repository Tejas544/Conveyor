# ARCHITECTURE.md §15.4: RDS Postgres (db.t4g.micro), private-subnet-only, reachable only from EKS
# worker nodes — the fallback path's replacement for the $0 path's in-cluster containerized Postgres
# (ARCHITECTURE.md §4 unchanged either way: one instance, one database + role per service).

resource "aws_db_subnet_group" "this" {
  name       = "${var.cluster_name}-rds"
  subnet_ids = [for s in aws_subnet.private : s.id]
}

resource "aws_security_group" "rds" {
  name_prefix = "${var.cluster_name}-rds-"
  vpc_id      = aws_vpc.this.id

  ingress {
    description     = "Postgres from EKS worker nodes only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.nodes.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.cluster_name}-rds-sg" }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.cluster_name}-postgres"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false

  multi_az                = false
  backup_retention_period = 1
  skip_final_snapshot     = true

  # ARCHITECTURE.md §15.4's own table: "Free 12 mo on a *new* account, billed after" — not
  # something Terraform can express as a cost control, only named here so the line item in that
  # table and this resource stay in sync.
}
