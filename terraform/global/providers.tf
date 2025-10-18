variable "aws_region"   { type = string  default = "eu-central-1" }
variable "zone_id"      { type = string }
variable "hostname"     { type = string } # e.g. "newsbot.example.com"
variable "primary_lb"   { type = string } # DNS name of primary LB (eu)
variable "secondary_lb" { type = string } # DNS name of secondary LB (us)

provider "aws" {
  region = var.aws_region
}
