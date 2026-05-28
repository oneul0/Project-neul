#!/bin/bash
# -------------------------------------------------------
# EC2 초기 설정 스크립트 (Ubuntu 22.04 기준)
# 실행: bash setup-ec2.sh
#
# 수행 작업:
#   1. 패키지 업데이트
#   2. Docker + Docker Compose 설치
#   3. 스왑 2GB 설정
#   4. 방화벽 설정 (ufw)
# -------------------------------------------------------

set -e

echo "========================================"
echo "  EC2 초기 설정 시작"
echo "========================================"

# ── 1. 패키지 업데이트 ──────────────────────────────────
echo ""
echo "[1/4] 패키지 업데이트..."
sudo apt-get update -y
sudo apt-get upgrade -y

# ── 2. Docker 설치 ──────────────────────────────────────
echo ""
echo "[2/4] Docker 설치..."

# 기존 구버전 제거
sudo apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true

# Docker 공식 GPG 키 + 저장소 추가
sudo apt-get install -y ca-certificates curl gnupg lsb-release
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update -y
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 현재 유저를 docker 그룹에 추가 (sudo 없이 docker 사용)
sudo usermod -aG docker $USER

sudo systemctl enable docker
sudo systemctl start docker

echo "Docker 설치 완료: $(docker --version)"
echo "Docker Compose 설치 완료: $(docker compose version)"

# ── 3. 스왑 설정 (2GB) ──────────────────────────────────
echo ""
echo "[3/4] 스왑 설정..."

SWAPFILE=/swapfile

if [ -f "$SWAPFILE" ]; then
    echo "스왑 파일이 이미 존재합니다. 건너뜁니다."
else
    sudo fallocate -l 2G $SWAPFILE
    sudo chmod 600 $SWAPFILE
    sudo mkswap $SWAPFILE
    sudo swapon $SWAPFILE

    # 재부팅 후에도 스왑 유지
    echo "$SWAPFILE none swap sw 0 0" | sudo tee -a /etc/fstab

    # 스왑 적극 사용 억제 (RAM 먼저 최대한 사용)
    echo "vm.swappiness=10" | sudo tee -a /etc/sysctl.conf
    sudo sysctl vm.swappiness=10

    echo "스왑 설정 완료:"
    free -h
fi

# ── 4. 방화벽 설정 ──────────────────────────────────────
echo ""
echo "[4/4] 방화벽 설정..."

sudo apt-get install -y ufw
sudo ufw allow ssh
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
# 내부 서비스 포트는 열지 않음 (Docker 네트워크 내부 통신)
sudo ufw --force enable

echo "방화벽 설정 완료:"
sudo ufw status

# ── 완료 ────────────────────────────────────────────────
echo ""
echo "========================================"
echo "  설정 완료!"
echo "========================================"
echo ""
echo "⚠️  docker 그룹 적용을 위해 재로그인 필요:"
echo "    exit 후 다시 ssh 접속하세요"
echo ""
echo "다음 단계:"
echo "  1. exit → 재접속"
echo "  2. git clone https://github.com/oneul0/Project-neul.git"
echo "  3. cd Project-neul"
echo "  4. cp .env.prod.example .env.prod && vi .env.prod"
echo "  5. docker compose -f docker-compose.prod.yml --env-file .env.prod up -d"
