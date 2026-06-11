#!/bin/sh
#
# Populate rootfs/ with a static busybox + the applets the OCI mounts and the
# user process need. Skips re-staging if rootfs/ already looks complete.
#
# Override BUSYBOX=/path/to/busybox if your host's busybox isn't on PATH or
# isn't statically linked.

set -eu

cd "$(dirname "$0")"

ROOTFS=rootfs

BUSYBOX="${BUSYBOX:-$(command -v busybox 2>/dev/null || true)}"
if [ -z "$BUSYBOX" ]; then
    echo "error: busybox not found on PATH" >&2
    echo "  Debian/Ubuntu: sudo apt-get install -y busybox-static" >&2
    echo "  Or set BUSYBOX=/path/to/static/busybox and re-run." >&2
    exit 1
fi

# Containers exec inside a rootfs with no shared libraries; the busybox must
# be a static binary. The check below is best-effort — some toolchains label
# static binaries oddly.
if ldd "$BUSYBOX" 2>/dev/null | grep -qv "not a dynamic executable"; then
    echo "warning: $BUSYBOX is dynamically linked; the container will likely" >&2
    echo "         fail with 'No such file or directory' on exec. Install" >&2
    echo "         busybox-static or supply BUSYBOX=/path/to/static/binary." >&2
fi

mkdir -p \
    "$ROOTFS/bin" \
    "$ROOTFS/etc" \
    "$ROOTFS/proc" \
    "$ROOTFS/sys" \
    "$ROOTFS/dev" \
    "$ROOTFS/tmp"

cp "$BUSYBOX" "$ROOTFS/bin/busybox"
chmod +x "$ROOTFS/bin/busybox"

# Symlink the applets this bundle's config.json (and ad-hoc poking) might use.
for cmd in sh sleep cat echo ls true false test mkdir rm chmod chown id; do
    ln -sf busybox "$ROOTFS/bin/$cmd"
done

# Minimal /etc files. takoyaki creates /etc/passwd and /etc/group entries
# automatically when spec.process.user.uid != 0, but seeding empties here
# avoids "no such user" surprises for tools that read these even at uid=0.
: > "$ROOTFS/etc/passwd"
: > "$ROOTFS/etc/group"

echo "rootfs ready at $(pwd)/$ROOTFS"
echo
echo "Try it:"
echo "  sudo takoyaki create --bundle \"$(pwd)\" demo"
echo "  sudo takoyaki start  demo"
echo "  sudo takoyaki state  demo"
echo "  sudo takoyaki kill   demo TERM"
echo "  sudo takoyaki delete demo"
