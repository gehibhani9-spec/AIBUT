#!/data/data/com.termux/files/usr/bin/bash
# Installs QEMU in Termux and adds helpers to convert a VMware .vmdk and run it (no root, software emulation).
set -e
pkg update -y
pkg install -y qemu-system-x86-64-headless qemu-system-aarch64-headless qemu-utils
mkdir -p "$HOME/vm"
cat > "$HOME/vm/convert.sh" <<'XEOF'
#!/data/data/com.termux/files/usr/bin/bash
# usage: convert.sh image.vmdk   -> image.qcow2
qemu-img convert -p -f vmdk -O qcow2 "$1" "${1%.*}.qcow2"
XEOF
cat > "$HOME/vm/run.sh" <<'XEOF'
#!/data/data/com.termux/files/usr/bin/bash
# usage: run.sh image.qcow2 [ram_mb]   then open a VNC viewer at localhost:5900
RAM="${2:-1536}"
exec qemu-system-x86_64 -machine q35 -cpu qemu64 -accel tcg,thread=multi -smp 4 -m "$RAM" \
  -drive file="$1",format=qcow2,if=virtio -netdev user,id=n0,hostfwd=tcp::2222-:22 \
  -device virtio-net-pci,netdev=n0 -display vnc=:0 -vga std
XEOF
chmod +x "$HOME/vm/convert.sh" "$HOME/vm/run.sh"
echo "QEMU ready. Put your .vmdk in ~/vm, run ./convert.sh file.vmdk, then ./run.sh file.qcow2"
