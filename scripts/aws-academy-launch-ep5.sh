#!/usr/bin/env bash
# =====================================================================
# EP5 - Crea la EC2 de Pedidos360 + Kafka desde la terminal del AWS Academy Learner Lab.
#   bash aws-academy-launch-ep5.sh <IP_PUBLICA_PERMITIDA> [tipo_instancia]
# Learner Lab: solo us-east-1/us-west-2, instancias hasta "large", key pair "vockey"
# (la terminal del lab ya tiene ~/.ssh/labsuser.pem) e instance profile LabInstanceProfile.
# El user-data instala Docker/Compose/Java 21, clona el repositorio y ejecuta
# scripts/ec2-deploy-ep5.sh (log en /home/ec2-user/deploy.log).
# =====================================================================
set -euo pipefail

ALLOWED_IP=${1:?Indique la IP publica permitida}
INSTANCE_TYPE=${2:-t3.large}
REPO=${REPO:-https://github.com/JpGonzalezA10/pedidos360-ev2.git}
BRANCH=${BRANCH:-feature/ep5-kafka}
NAME=pedidos360-ep5
REGION=us-east-1

AMI_ID=$(aws ssm get-parameter --region "$REGION" \
  --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --query Parameter.Value --output text)
VPC_ID=$(aws ec2 describe-vpcs --region "$REGION" --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text)
SG_ID=$(aws ec2 describe-security-groups --region "$REGION" \
  --filters Name=group-name,Values="$NAME-sg" --query 'SecurityGroups[0].GroupId' --output text)
if [ "$SG_ID" = "None" ]; then
  SG_ID=$(aws ec2 create-security-group --region "$REGION" --group-name "$NAME-sg" \
    --description "Pedidos360 EP5 - SSH, microservicios, Kafka-UI y RabbitMQ Management" --vpc-id "$VPC_ID" \
    --query GroupId --output text)
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" \
    --protocol tcp --port 22 --cidr 0.0.0.0/0 > /dev/null
  aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" --ip-permissions \
    "IpProtocol=tcp,FromPort=8080,ToPort=8086,IpRanges=[{CidrIp=$ALLOWED_IP/32,Description=Microservicios}]" \
    "IpProtocol=tcp,FromPort=8090,ToPort=8090,IpRanges=[{CidrIp=$ALLOWED_IP/32,Description=Kafka-UI}]" \
    "IpProtocol=tcp,FromPort=15672,ToPort=15672,IpRanges=[{CidrIp=$ALLOWED_IP/32,Description=RabbitMQ-Management}]" > /dev/null
fi

cat > /tmp/user-data-ep5.sh <<EOF
#!/bin/bash
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
dnf install -y docker git jq java-21-amazon-corretto-headless java-21-amazon-corretto-devel tar util-linux
systemctl enable --now docker
usermod -aG docker ec2-user
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL https://github.com/docker/compose/releases/download/v2.39.2/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
curl -fsSL https://github.com/docker/buildx/releases/download/v0.26.1/buildx-v0.26.1.linux-amd64 \
  -o /usr/local/lib/docker/cli-plugins/docker-buildx
chmod +x /usr/local/lib/docker/cli-plugins/*
touch /var/tmp/bootstrap-done
sudo -u ec2-user git clone -b $BRANCH $REPO /home/ec2-user/pedidos360
sudo -u ec2-user bash -c 'cd ~ && nohup bash ~/pedidos360/scripts/ec2-deploy-ep5.sh > ~/deploy.log 2>&1 &'
EOF

INSTANCE_ID=$(aws ec2 run-instances --region "$REGION" --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" --key-name vockey --security-group-ids "$SG_ID" \
  --iam-instance-profile Name=LabInstanceProfile \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":40,"VolumeType":"gp3"}}]' \
  --user-data file:///tmp/user-data-ep5.sh \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NAME}]" \
  --query 'Instances[0].InstanceId' --output text)

aws ec2 wait instance-running --region "$REGION" --instance-ids "$INSTANCE_ID"
PUBLIC_IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)

echo "INSTANCE_ID=$INSTANCE_ID" | tee ~/$NAME.env
echo "PUBLIC_IP=$PUBLIC_IP" | tee -a ~/$NAME.env
echo "SG_ID=$SG_ID" | tee -a ~/$NAME.env
echo "Conectar: ssh -i ~/.ssh/labsuser.pem ec2-user@$PUBLIC_IP"
