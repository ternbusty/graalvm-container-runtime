#!/bin/sh
#
# Stage a rootfs with just enough to satisfy the spec: /bin/true and the
# empty mount-point directories the kernel expects.

set -eu

cd "$(dirname "$0")"

ROOTFS=rootfs

BUSYBOX="${BUSYBOX:-$(command -v busybox 2>/dev/null || true)}"
if [ -z "$BUSYBOX" ]; then
    echo "error: busybox not found (need it for /bin/true)" >&2
    echo "  Debian/Ubuntu: sudo apt-get install -y busybox-static" >&2
    exit 1
fi

mkdir -p "$ROOTFS/bin"
cp "$BUSYBOX" "$ROOTFS/bin/busybox"
ln -sf busybox "$ROOTFS/bin/true"

echo "rootfs ready at $(pwd)/$ROOTFS"
echo
echo "Try it:"
echo "  sudo takoyaki create --bundle \"$(pwd)\" tiny"
echo "  sudo takoyaki start  tiny"
echo "  sudo takoyaki delete --force tiny"
