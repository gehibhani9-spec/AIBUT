#!/data/data/com.termux/files/usr/bin/bash
# Installs a Kali Linux rootfs in Termux using proot (no root needed).
set -e
pkg update -y
pkg install -y proot wget tar xz-utils
cd "$HOME"
if [ ! -d "$HOME/kali-arm64" ]; then
  wget -O kali-rootfs.tar.xz https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz
  proot --link2symlink tar -xJf kali-rootfs.tar.xz -C "$HOME" --exclude='dev'
  rm -f kali-rootfs.tar.xz
fi
mkdir -p "$HOME/kali-arm64/tmp" "$HOME/kali-arm64/root"
echo "nameserver 8.8.8.8" > "$HOME/kali-arm64/etc/resolv.conf"
cat > "$HOME/start-kali.sh" <<'XEOF'
#!/data/data/com.termux/files/usr/bin/bash
unset LD_PRELOAD
if [ $# -gt 0 ]; then CMD=("/bin/bash" "-lc" "$*"); else CMD=("/bin/bash" "--login"); fi
exec proot --link2symlink -0 -r "$HOME/kali-arm64" \
  -b /dev -b /proc -b /sys -b "$HOME:/root/termux-home" -w /root \
  /usr/bin/env -i HOME=/root TERM="$TERM" LANG=C.UTF-8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "${CMD[@]}"
XEOF
chmod +x "$HOME/start-kali.sh"
echo "Kali is ready. Start it with: bash ~/start-kali.sh"
echo "Then inside Kali: apt update && apt install -y kali-tools-top10"
