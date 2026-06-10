# takoyaki

A GraalVM Native Image OCI container runtime, written in Java + Panama FFM. Implements the [OCI Runtime Specification](https://github.com/opencontainers/runtime-spec).

## Install

Prebuilt static binaries are published on the [GitHub Releases](https://github.com/ternbusty/takoyaki/releases) page for each tag. Pick the asset that matches your host architecture.

```sh
# aarch64 / arm64
sudo curl -sSL -o /usr/local/bin/takoyaki \
    https://github.com/ternbusty/takoyaki/releases/latest/download/takoyaki-linux-aarch64
sudo chmod +x /usr/local/bin/takoyaki

# x86_64 / amd64
sudo curl -sSL -o /usr/local/bin/takoyaki \
    https://github.com/ternbusty/takoyaki/releases/latest/download/takoyaki-linux-x86_64
sudo chmod +x /usr/local/bin/takoyaki
```

A sha256 checksum is shipped alongside each binary as `<binary-name>.sha256`. Verify with:

```sh
curl -sSL https://github.com/ternbusty/takoyaki/releases/latest/download/takoyaki-linux-aarch64.sha256 \
    | sha256sum -c
```

### Runtime requirements

- Linux 5.12+ (for idmap mount; older kernels still work for simpler bundles)
- libseccomp 2.x (`libseccomp2` on Debian/Ubuntu, `libseccomp` on Fedora). The binary loads `libseccomp.so.2` at runtime
- cgroup v2 mounted at `/sys/fs/cgroup` (cgroup v1 is not supported)

## Usage

```sh
takoyaki create   --bundle ./bundle  my-container
takoyaki start    my-container
takoyaki state    my-container
takoyaki kill     my-container TERM
takoyaki delete   --force my-container
```

A pidfile path can be supplied with `--pid-file`, and console PTY support is wired through `--console-socket`. See `takoyaki <subcommand> --help` for the full flag list.

## Build from source

You need GraalVM Community 25 and libseccomp-dev installed.

```sh
./gradlew nativeCompile
# Produces build/native/nativeCompile/takoyaki
```

Pass `-Pquick` for a fast development image:

```sh
./gradlew nativeCompile -Pquick
```

Unit tests run in any JVM:

```sh
./gradlew test
```

Contest-style integration tests (modelled on youki's `tests/contest/`) drive the real binary. They require root on Linux:

```sh
sudo -E env "TAKOYAKI_BIN=$PWD/build/native/nativeCompile/takoyaki" ./gradlew contestTest
```

## Releases

Releases are managed by [release-please](https://github.com/googleapis/release-please) using conventional-commit messages. Merging a release PR tags `vX.Y.Z`, and the `release` workflow then native-image-builds the binaries for both linux/aarch64 and linux/x86_64 and attaches them to the GitHub Release.

Commit message format follows [Conventional Commits](https://www.conventionalcommits.org/):

- `feat: ...` triggers a minor bump
- `fix: ...` triggers a patch bump
- `feat!: ...` or `BREAKING CHANGE:` triggers a major bump
- `chore: ...`, `docs: ...`, `refactor: ...`, etc. flow into the changelog without bumping unless they include a breaking change footer
