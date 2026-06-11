# takoyaki examples

Sample OCI bundles you can drive end-to-end with `takoyaki create / start / state / kill / delete`.

## Prerequisites

- Linux with cgroup v2 (Ubuntu 22.04+, Fedora 31+, anything new)
- `libseccomp2` installed on the host (`apt-get install libseccomp2` or distro equivalent)
- A static busybox available locally (`apt-get install busybox-static`, or any prebuilt). Each example's `setup.sh` will detect it.
- root (or `sudo`). takoyaki unshares PID / mount / IPC / UTS / network, which the kernel rejects without `CAP_SYS_ADMIN` outside a user namespace.

If you don't have takoyaki yet, grab the release binary:

```sh
sudo curl -sSL -o /usr/local/bin/takoyaki \
    https://github.com/ternbusty/takoyaki/releases/latest/download/takoyaki-linux-$(uname -m)
sudo chmod +x /usr/local/bin/takoyaki
```

## Bundles

### `busybox-sleep/` — full lifecycle demo

Boots a static busybox running `sleep 60`. Long-lived enough to demonstrate every lifecycle command.

```sh
cd examples/busybox-sleep
./setup.sh                                # populates rootfs/ from /bin/busybox

sudo takoyaki create --bundle . demo      # parks in 'created'
sudo takoyaki state demo                  # → status: created, pid: <init>
sudo takoyaki start demo                  # exec into the sleep
sudo takoyaki state demo                  # → status: running
sudo takoyaki kill demo KILL              # stop (busybox sleep ignores TERM)
sudo takoyaki state demo                  # → status: stopped
sudo takoyaki delete demo                 # teardown + cgroup rmdir
```

`sudo takoyaki list` shows everything currently in `/run/takoyaki`.

### `minimal/` — smallest valid spec

The bare-minimum config.json that satisfies the OCI runtime spec. Useful as a starting point for hand-rolling your own bundle. It uses `/bin/true` so it stops as soon as start fires; combine with `delete --force` for cleanup.

```sh
cd examples/minimal
./setup.sh
sudo takoyaki create --bundle . tiny
sudo takoyaki start tiny
sudo takoyaki delete --force tiny
```

## Cleaning up

Bundle rootfs directories are git-ignored. To start fresh:

```sh
rm -rf examples/busybox-sleep/rootfs examples/minimal/rootfs
```

If you ever leave a container partially-created (Ctrl-C during create, kernel panic, ...), takoyaki's state lives under `/run/takoyaki/<id>/`. `sudo rm -rf /run/takoyaki/<id>` clears it.

## Writing your own bundle

The OCI Runtime Specification is at <https://github.com/opencontainers/runtime-spec/blob/main/config.md>. The `busybox-sleep/config.json` is annotated by section and is a fine starting point. Fields takoyaki understands but doesn't strictly require: `process.capabilities`, `process.rlimits`, `linux.resources`, `linux.maskedPaths`, `linux.readonlyPaths`, `hooks.{prestart,createRuntime,poststart,poststop}`, `annotations`.
