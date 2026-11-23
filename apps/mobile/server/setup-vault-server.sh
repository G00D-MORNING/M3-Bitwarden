#!/usr/bin/env bash
# M3 Bitwarden – Vaultwarden hinter Cloudflare Tunnel auf einem Ubuntu-24.04-KVM.
#
# Was das Skript macht (idempotent, kann mehrfach laufen):
#   1. Legt einen LUKS2-verschlüsselten Container für alle Tresordaten an
#      (Passphrase nur im Kopf / Passwortmanager, nie auf der Platte).
#   2. Startet Vaultwarden (Bitwarden-kompatibel, Ende-zu-Ende-verschlüsselt,
#      mehrere Accounts) als gehärteten Docker-Container, nur auf 127.0.0.1.
#   3. Installiert cloudflared als Systemdienst mit dem Tunnel-Token
#      (Token wird verdeckt abgefragt, landet nicht in der Shell-History).
#   4. Richtet nächtliche, mit age verschlüsselte Backups und wöchentliche
#      Image-Updates ein.
#
# Kein Port wird nach außen geöffnet. Bestehende Dienste (NPM, Mail, Plex …)
# werden nicht angefasst.
#
# Aufruf als root:   bash setup-vault-server.sh
# Nach einem Reboot: m3b-vault-unlock   (Passphrase eingeben, Tresor startet)

set -euo pipefail

WEB_DOMAIN="${WEB_DOMAIN:-tresor.jochenderrochen.de}"
VAULT_IMAGE="${VAULT_IMAGE:-vaultwarden/server:latest}"
LUKS_IMAGE="${LUKS_IMAGE:-/srv/m3b-vault.img}"
LUKS_SIZE_GB="${LUKS_SIZE_GB:-4}"
LUKS_NAME="m3b-vault"
DATA_DIR="/srv/m3b-vault/data"
APP_DIR="/opt/m3b-vault"
BACKUP_DIR="/var/backups/m3b-vault"
VAULT_UID=10001

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

require_root() {
  [[ ${EUID} -eq 0 ]] || die "Bitte als root ausführen."
  # shellcheck disable=SC1091
  . /etc/os-release
  [[ "${ID}" == "ubuntu" ]] || warn "Getestet für Ubuntu 24.04, gefunden: ${PRETTY_NAME}"
}

check_disk_space() {
  [[ -f "${LUKS_IMAGE}" ]] && return 0
  local free_gb
  free_gb=$(df --output=avail -BG /srv | tail -1 | tr -dc '0-9')
  local needed=$((LUKS_SIZE_GB + 2))
  if (( free_gb < needed )); then
    die "Nur ${free_gb} GB frei auf /srv, gebraucht werden ${needed} GB. Erst Platz schaffen oder LUKS_SIZE_GB kleiner setzen."
  fi
  log "Speicher ok: ${free_gb} GB frei, Container bekommt ${LUKS_SIZE_GB} GB."
}

install_packages() {
  log "Pakete installieren (cryptsetup, sqlite3, age, curl)."
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq cryptsetup sqlite3 age curl ca-certificates gnupg >/dev/null
  if ! command -v docker >/dev/null; then
    log "Docker fehlt, installiere docker.io und Compose-Plugin."
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io docker-compose-v2 >/dev/null
    systemctl enable --now docker
  fi
  docker compose version >/dev/null 2>&1 || die "docker compose fehlt. Bitte das Compose-Plugin installieren."
}

setup_luks() {
  if [[ -f "${LUKS_IMAGE}" ]] && ! cryptsetup isLuks "${LUKS_IMAGE}"; then
    warn "${LUKS_IMAGE} ist kein fertiger LUKS-Container (abgebrochener Lauf), wird neu angelegt."
    rm -f "${LUKS_IMAGE}"
  fi
  if [[ ! -f "${LUKS_IMAGE}" ]]; then
    log "Verschlüsselten Container ${LUKS_IMAGE} (${LUKS_SIZE_GB} GB) anlegen."
    fallocate -l "${LUKS_SIZE_GB}G" "${LUKS_IMAGE}"
    chmod 600 "${LUKS_IMAGE}"
    echo
    echo "Jetzt die LUKS-Passphrase festlegen. Lang und zufällig, z. B. aus deinem Passwortmanager."
    echo "Ohne sie startet der Tresor nach einem Reboot nicht und die Daten sind nicht wiederherstellbar."
    cryptsetup luksFormat -q --verify-passphrase --type luks2 --pbkdf argon2id --cipher aes-xts-plain64 --key-size 512 "${LUKS_IMAGE}"
    cryptsetup open "${LUKS_IMAGE}" "${LUKS_NAME}"
    mkfs.ext4 -q -L m3b-vault "/dev/mapper/${LUKS_NAME}"
  elif [[ ! -e "/dev/mapper/${LUKS_NAME}" ]]; then
    log "Container existiert, wird geöffnet."
    cryptsetup open "${LUKS_IMAGE}" "${LUKS_NAME}"
  fi
  mkdir -p "${DATA_DIR}"
  if ! mountpoint -q "${DATA_DIR}"; then
    mount -o nodev,nosuid,noexec "/dev/mapper/${LUKS_NAME}" "${DATA_DIR}"
  fi
  chown "${VAULT_UID}:${VAULT_UID}" "${DATA_DIR}"
  chmod 700 "${DATA_DIR}"
}

write_compose() {
  log "Vaultwarden-Konfiguration nach ${APP_DIR} schreiben."
  mkdir -p "${APP_DIR}"
  chmod 700 "${APP_DIR}"
  cat > "${APP_DIR}/compose.yaml" <<EOF
services:
  vaultwarden:
    image: ${VAULT_IMAGE}
    container_name: m3b-vaultwarden
    restart: unless-stopped
    user: "${VAULT_UID}:${VAULT_UID}"
    read_only: true
    tmpfs:
      - /tmp
    cap_drop: [ALL]
    security_opt:
      - no-new-privileges:true
    ports:
      - "127.0.0.1:8222:8080"
    volumes:
      - ${DATA_DIR}:/data
    environment:
      DOMAIN: "https://${WEB_DOMAIN}"
      ROCKET_PORT: "8080"
      SIGNUPS_ALLOWED: "true"
      INVITATIONS_ALLOWED: "true"
      SHOW_PASSWORD_HINT: "false"
      PASSWORD_HINTS_ALLOWED: "false"
      IP_HEADER: "CF-Connecting-IP"
      LOGIN_RATELIMIT_SECONDS: "60"
      LOGIN_RATELIMIT_MAX_BURST: "10"
      LOG_LEVEL: "warn"
      EXTENDED_LOGGING: "true"
EOF
  # Kein ADMIN_TOKEN gesetzt: Die Admin-Oberfläche ist damit komplett aus.
  # Zusätzlich blockt Cloudflare /admin auf beiden Adressen.
}

start_vault() {
  log "Vaultwarden starten."
  docker compose -f "${APP_DIR}/compose.yaml" pull -q
  docker compose -f "${APP_DIR}/compose.yaml" up -d
  local _
  for _ in $(seq 1 30); do
    if curl -fsS -o /dev/null http://127.0.0.1:8222/alive; then
      log "Vaultwarden läuft lokal auf 127.0.0.1:8222."
      return 0
    fi
    sleep 2
  done
  die "Vaultwarden antwortet nicht. Logs: docker logs m3b-vaultwarden"
}

install_cloudflared() {
  if ! command -v cloudflared >/dev/null; then
    log "cloudflared aus dem offiziellen Cloudflare-Repository installieren."
    install -d -m 0755 /usr/share/keyrings
    curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg -o /usr/share/keyrings/cloudflare-main.gpg
    echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" \
      > /etc/apt/sources.list.d/cloudflared.list
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq cloudflared >/dev/null
  fi
  if systemctl is-active --quiet cloudflared; then
    log "cloudflared-Dienst läuft bereits."
    return 0
  fi
  echo
  echo "Tunnel-Token einfügen (Cloudflare → Networking → Tunnels → m3b-vault-koeln → Linux-Befehl, nur der Teil nach 'install')."
  echo "Die Eingabe bleibt unsichtbar."
  local token
  read -rs -p "Token: " token
  echo
  [[ -n "${token}" ]] || die "Kein Token eingegeben."
  cloudflared service install "${token}" >/dev/null
  unset token
  systemctl enable --now cloudflared
  log "cloudflared läuft als Dienst."
}

install_unlock_helper() {
  log "Hilfsbefehl m3b-vault-unlock für die Zeit nach einem Reboot anlegen."
  cat > /usr/local/sbin/m3b-vault-unlock <<EOF
#!/usr/bin/env bash
set -euo pipefail
[[ -e /dev/mapper/${LUKS_NAME} ]] || cryptsetup open ${LUKS_IMAGE} ${LUKS_NAME}
mountpoint -q ${DATA_DIR} || mount -o nodev,nosuid,noexec /dev/mapper/${LUKS_NAME} ${DATA_DIR}
docker compose -f ${APP_DIR}/compose.yaml up -d
echo "Tresor ist entsperrt und läuft."
EOF
  chmod 700 /usr/local/sbin/m3b-vault-unlock

  cat > /usr/local/sbin/m3b-vault-lock <<EOF
#!/usr/bin/env bash
set -euo pipefail
docker compose -f ${APP_DIR}/compose.yaml down
mountpoint -q ${DATA_DIR} && umount ${DATA_DIR}
[[ -e /dev/mapper/${LUKS_NAME} ]] && cryptsetup close ${LUKS_NAME}
echo "Tresor ist gesperrt, Daten liegen nur noch verschlüsselt auf der Platte."
EOF
  chmod 700 /usr/local/sbin/m3b-vault-lock
}

install_backups() {
  if [[ -z "${AGE_RECIPIENT:-}" ]]; then
    warn "AGE_RECIPIENT nicht gesetzt, Backups werden übersprungen."
    warn "Später nachholen: AGE_RECIPIENT='age1…' bash $0"
    return 0
  fi
  log "Nächtliche verschlüsselte Backups nach ${BACKUP_DIR} einrichten."
  mkdir -p "${BACKUP_DIR}"
  chmod 700 "${BACKUP_DIR}"
  echo "${AGE_RECIPIENT}" > "${APP_DIR}/backup-recipient.txt"
  cat > /usr/local/sbin/m3b-vault-backup <<EOF
#!/usr/bin/env bash
set -euo pipefail
mountpoint -q ${DATA_DIR} || { echo "Tresor gesperrt, kein Backup."; exit 0; }
work=\$(mktemp -d)
trap 'rm -rf "\$work"' EXIT
sqlite3 ${DATA_DIR}/db.sqlite3 ".backup '\$work/db.sqlite3'"
tar -C ${DATA_DIR} --exclude=db.sqlite3 --exclude='db.sqlite3-*' --exclude=icon_cache -cf "\$work/files.tar" .
tar -C "\$work" -cf - db.sqlite3 files.tar | age -R ${APP_DIR}/backup-recipient.txt \
  > "${BACKUP_DIR}/vault-\$(date +%Y%m%d-%H%M).tar.age"
ls -1t ${BACKUP_DIR}/vault-*.tar.age | tail -n +15 | xargs -r rm --
EOF
  chmod 700 /usr/local/sbin/m3b-vault-backup

  cat > /etc/systemd/system/m3b-vault-backup.service <<'EOF'
[Unit]
Description=M3 Bitwarden: verschlüsseltes Tresor-Backup
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/m3b-vault-backup
EOF
  cat > /etc/systemd/system/m3b-vault-backup.timer <<'EOF'
[Unit]
Description=M3 Bitwarden: nächtliches Backup
[Timer]
OnCalendar=*-*-* 03:30:00
RandomizedDelaySec=20m
Persistent=true
[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now m3b-vault-backup.timer
}

install_updates() {
  log "Wöchentliche Vaultwarden-Updates einrichten."
  cat > /etc/systemd/system/m3b-vault-update.service <<EOF
[Unit]
Description=M3 Bitwarden: Vaultwarden-Image aktualisieren
ConditionPathIsMountPoint=${DATA_DIR}
[Service]
Type=oneshot
ExecStart=/usr/bin/docker compose -f ${APP_DIR}/compose.yaml pull -q
ExecStart=/usr/bin/docker compose -f ${APP_DIR}/compose.yaml up -d
EOF
  cat > /etc/systemd/system/m3b-vault-update.timer <<'EOF'
[Unit]
Description=M3 Bitwarden: wöchentliches Update
[Timer]
OnCalendar=Mon *-*-* 04:15:00
RandomizedDelaySec=30m
Persistent=true
[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now m3b-vault-update.timer
}

verify() {
  log "Prüfen, dass Vaultwarden nicht von außen erreichbar ist."
  if ss -Hltn 'sport = :8222' | grep -vq '127.0.0.1:8222'; then
    die "Port 8222 lauscht nicht nur auf 127.0.0.1. Bitte compose.yaml prüfen."
  fi
  log "Port 8222 nur lokal: ok."
  if systemctl is-active --quiet cloudflared; then
    log "cloudflared aktiv: ok."
  else
    warn "cloudflared läuft nicht."
  fi
  echo
  echo "Fertig. Nächste Schritte:"
  echo "  1. https://${WEB_DOMAIN} öffnen, mit deiner E-Mail und Einmalcode durch Cloudflare Access,"
  echo "     dann Accounts anlegen (beliebig viele)."
  echo "  2. Nach jedem Neustart des Servers: m3b-vault-unlock"
}

main() {
  require_root
  check_disk_space
  install_packages
  setup_luks
  write_compose
  start_vault
  install_cloudflared
  install_unlock_helper
  install_backups
  install_updates
  verify
}

main "$@"
