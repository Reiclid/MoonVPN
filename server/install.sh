#!/usr/bin/env bash
# Один файл: sudo ./install.sh
# Ставить Docker, піднімає WireGuard + AdGuard Home, вмикає автозапуск
# (переживає вимкнення світла) і друкує QR для телефону.
#   sudo ./install.sh                  - встановити / оновити
#   sudo ./install.sh --host my.ddns   - явно вказати зовнішній IP/домен
#   sudo ./install.sh add <name>       - додати ще один пристрій
#   sudo ./install.sh show [name]      - показати QR існуючого клієнта
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
DIR="$PWD"

[ "$(id -u)" -eq 0 ] || { echo "Запусти з sudo: sudo ./install.sh"; exit 1; }

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

show_client() {
  local name="${1:-phone}" f="$DIR/data/wireguard/peer_$1/peer_$1.conf"
  [ -f "$f" ] || { echo "Клієнта '$name' не знайдено"; return 1; }
  mkdir -p "$DIR/clients"; cp "$f" "$DIR/clients/$name.conf"; chmod 600 "$DIR/clients/$name.conf"
  echo "Конфіг: $DIR/clients/$name.conf  (скопіюй у додаток або скануй QR)"
  command -v qrencode >/dev/null && qrencode -t ansiutf8 < "$f" || cat "$f"
}

wait_peer() {
  for _ in $(seq 1 90); do [ -f "$DIR/data/wireguard/peer_$1/peer_$1.conf" ] && return 0; sleep 1; done
  echo "Конфіг клієнта $1 не з'явився, дивись: docker logs pivpn-wireguard"; return 1
}

# ---------- команди add / show ----------
if [ "${1:-}" = "add" ]; then
  [ -n "${2:-}" ] || { echo "Вкажи ім'я: ./install.sh add tablet"; exit 1; }
  [[ "$2" =~ ^[A-Za-z0-9]+$ ]] || { echo "Ім'я: лише літери й цифри"; exit 1; }
  . ./.env
  grep -qw "$2" <<<"${PEERS//,/ }" || sed -i "s/^PEERS=.*/PEERS=${PEERS},$2/" .env
  docker compose up -d --force-recreate wireguard
  wait_peer "$2"; show_client "$2"; exit 0
fi
if [ "${1:-}" = "show" ]; then show_client "${2:-phone}"; exit $?; fi

HOST_ARG=""
[ "${1:-}" = "--host" ] && HOST_ARG="${2:-}"

# ---------- 1. пакети та Docker ----------
log "Перевіряю залежності"
export DEBIAN_FRONTEND=noninteractive
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  echo "Docker і compose вже встановлені — пропускаю (існуючі контейнери не чіпаю)"
else
  apt-get update -y
  command -v docker >/dev/null || { log "Встановлюю Docker"; curl -fsSL https://get.docker.com | sh; }
  docker compose version >/dev/null 2>&1 || apt-get install -y docker-compose-plugin
fi
systemctl enable --now docker
# необов'язкові додатки (qrencode для QR, ufw/fail2ban для захисту): помилка тут не зупиняє установку
apt-get install -y --no-install-recommends qrencode ufw fail2ban unattended-upgrades curl ca-certificates   || echo "УВАГА: не вдалось встановити частину пакетів (немає інтернету/apt?), продовжую"
modprobe wireguard 2>/dev/null || true
echo wireguard > /etc/modules-load.d/wireguard.conf

# ---------- 2. налаштування (.env) ----------
if [ ! -f .env ]; then
  log "Створюю .env"
  HOST="${HOST_ARG:-$(curl -4fsS --max-time 10 https://api.ipify.org || true)}"
  [ -n "$HOST" ] || { echo "Не вдалось визначити зовнішній IP. Запусти: sudo ./install.sh --host <IP або домен>"; exit 1; }
  cat > .env <<ENV
SERVER_HOST=$HOST
WG_PORT=51820
PEERS=phone
TZ=$(cat /etc/timezone 2>/dev/null || echo Europe/Kyiv)
ENV
elif [ -n "$HOST_ARG" ]; then
  sed -i "s/^SERVER_HOST=.*/SERVER_HOST=$HOST_ARG/" .env
fi
. ./.env

# ---------- 3. AdGuard Home (пароль і фільтри) ----------
if [ ! -f data/adguard/conf/AdGuardHome.yaml ]; then
  log "Налаштовую AdGuard Home"
  mkdir -p data/adguard/conf data/adguard/work data/wireguard
  PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)"
  HASH="$(docker run --rm httpd:2.4-alpine htpasswd -nbB admin "$PASS" | cut -d: -f2- | tr -d '\n')"
  sed "s|__BCRYPT__|${HASH//|/\|}|" templates/AdGuardHome.yaml.tpl > data/adguard/conf/AdGuardHome.yaml
  umask 077; printf 'login: admin\npassword: %s\n' "$PASS" > adguard-password.txt
  chmod 600 adguard-password.txt
fi

# ---------- 4. запуск ----------
log "Піднімаю контейнери"
docker compose pull
docker compose up -d --remove-orphans

# ---------- 5. автозапуск після відключення світла ----------
log "Автозапуск (systemd)"
cat > /etc/systemd/system/pivpn.service <<UNIT
[Unit]
Description=Pi VPN (WireGuard + AdGuard Home)
Requires=docker.service
After=docker.service network-online.target time-sync.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=$DIR
ExecStart=/usr/bin/docker compose up -d --remove-orphans
ExecStop=/usr/bin/docker compose stop
TimeoutStartSec=600

[Install]
WantedBy=multi-user.target
UNIT
# щогодини перевіряємо, що все працює (на випадок, якщо щось видалили/впало)
cat > /etc/systemd/system/pivpn-check.service <<UNIT
[Unit]
Description=Pi VPN check
[Service]
Type=oneshot
WorkingDirectory=$DIR
ExecStart=/usr/bin/docker compose up -d --remove-orphans
UNIT
cat > /etc/systemd/system/pivpn-check.timer <<UNIT
[Unit]
Description=Pi VPN check every 5 min
[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
[Install]
WantedBy=timers.target
UNIT
# апаратний watchdog: малинка сама перезавантажиться, якщо зависне
mkdir -p /etc/systemd/system.conf.d
printf '[Manager]\nRuntimeWatchdogSec=15\nRebootWatchdogSec=2min\n' > /etc/systemd/system.conf.d/watchdog.conf
systemctl daemon-reload
systemctl enable pivpn.service pivpn-check.timer
systemctl start pivpn-check.timer

# ---------- 6. захист хоста ----------
log "Файервол і захист"
ufw allow 22/tcp >/dev/null
ufw allow "${WG_PORT}/udp" >/dev/null
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw --force enable >/dev/null
systemctl enable --now fail2ban
dpkg-reconfigure -f noninteractive unattended-upgrades || true

# ---------- 7. клієнти ----------
for p in ${PEERS//,/ }; do wait_peer "$p" && { echo; echo "--- $p ---"; show_client "$p"; }; done

cat <<MSG

Готово. На роутері прокинь UDP ${WG_PORT} -> ця малинка.
Панель AdGuard (тільки через VPN або SSH-тунель):
  VPN:  http://10.8.1.2:3000     SSH:  ssh -L 3000:localhost:${UI_PORT:-3000} user@${SERVER_HOST}
Пароль: $DIR/adguard-password.txt
Після вимкнення світла все стартує саме.
MSG
