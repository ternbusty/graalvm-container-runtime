#!/bin/bash
# Run on the VM after `cp build/native/nativeCompile/takoyaki /home/ubuntu/work/takoyaki`.
# Verifies every feature that was implemented but not yet tested in this session.
set -e
BIN=/home/ubuntu/work/takoyaki

cleanup() {
  sudo /home/ubuntu/work/takoyaki delete --force "$1" 2>/dev/null || true
  sudo rm -rf /run/takoyaki "/tmp/takoyaki-$1.sock" 2>/dev/null || true
}

echo "===== A. AppArmor (in-container effect) ====="
sudo tee /etc/apparmor.d/takoyaki-test > /dev/null <<'EOF'
abi <abi/4.0>,
include <tunables/global>
profile takoyaki-test flags=(attach_disconnected) {
  include <abstractions/base>
  /** rwklmix,
  deny /tmp/aa-deny w,
}
EOF
sudo apparmor_parser -r /etc/apparmor.d/takoyaki-test
mkdir -p /home/ubuntu/work/aa-bundle/rootfs/{bin,tmp}
cp /bin/busybox /home/ubuntu/work/aa-bundle/rootfs/bin/
( cd /home/ubuntu/work/aa-bundle/rootfs/bin && for c in sh ls cat echo grep; do ln -sf busybox "$c"; done )
cat > /home/ubuntu/work/aa-bundle/config.json <<'EOF'
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","{ echo profile=$(cat /proc/self/attr/current); echo deny=$(echo X > /tmp/aa-deny 2>&1 && echo wrote || echo blocked); echo allow=$(echo X > /tmp/aa-allow 2>&1 && echo wrote || echo blocked); } > /tmp/r.txt"],
             "env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0},"apparmorProfile":"takoyaki-test"},
  "hostname":"aa","linux":{"namespaces":[{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"}],"cgroupsPath":"/takoyaki-aa"} }
EOF
cleanup aa
( cd /home/ubuntu/work/aa-bundle && sudo $BIN create --bundle . aa >/dev/null && sudo $BIN start aa >/dev/null )
sleep 1
cat /home/ubuntu/work/aa-bundle/rootfs/tmp/r.txt
cleanup aa
sudo rmdir /sys/fs/cgroup/takoyaki-aa 2>/dev/null || true

echo
echo "===== B. poststart hook ====="
sudo rm -f /tmp/pre.txt /tmp/post.txt /tmp/stop.txt
cat > /home/ubuntu/work/hook-bundle/pre.sh <<'EOF'
#!/bin/bash
read X; date > /tmp/pre.txt
EOF
cat > /home/ubuntu/work/hook-bundle/post.sh <<'EOF'
#!/bin/bash
read X; date > /tmp/post.txt
EOF
cat > /home/ubuntu/work/hook-bundle/stop.sh <<'EOF'
#!/bin/bash
read X; date > /tmp/stop.txt
EOF
chmod +x /home/ubuntu/work/hook-bundle/{pre,post,stop}.sh
cat > /home/ubuntu/work/hook-bundle/config.json <<EOF
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","sleep 1"],"env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0}},
  "hostname":"h","linux":{"namespaces":[{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"}],"cgroupsPath":"/takoyaki-h"},
  "hooks":{"prestart":[{"path":"/home/ubuntu/work/hook-bundle/pre.sh"}],
           "poststart":[{"path":"/home/ubuntu/work/hook-bundle/post.sh"}],
           "poststop":[{"path":"/home/ubuntu/work/hook-bundle/stop.sh"}]} }
EOF
cleanup hk
( cd /home/ubuntu/work/hook-bundle && sudo $BIN create --bundle . hk >/dev/null && sudo $BIN start hk >/dev/null )
sleep 2
sudo $BIN delete --force hk >/dev/null
sudo rmdir /sys/fs/cgroup/takoyaki-h 2>/dev/null || true
echo "prestart=$(test -f /tmp/pre.txt && echo FIRED || echo NO)"
echo "poststart=$(test -f /tmp/post.txt && echo FIRED || echo NO)"
echo "poststop=$(test -f /tmp/stop.txt && echo FIRED || echo NO)"

echo
echo "===== C. cpuset ====="
cat > /home/ubuntu/work/hook-bundle/config.json <<EOF
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","cat /sys/fs/cgroup/cpuset.cpus 2>&1 > /tmp/cpus.txt"],
             "env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0}},
  "hostname":"c","linux":{"namespaces":[{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"}],
                          "cgroupsPath":"/takoyaki-cs",
                          "resources":{"cpu":{"cpus":"0"}}} }
EOF
cleanup cs
( cd /home/ubuntu/work/hook-bundle && sudo $BIN create --bundle . cs >/dev/null && sudo $BIN start cs >/dev/null )
sleep 1
echo "cpuset.cpus inside container: $(cat /home/ubuntu/work/hook-bundle/rootfs/tmp/cpus.txt 2>&1)"
sudo $BIN delete --force cs >/dev/null
sudo rmdir /sys/fs/cgroup/takoyaki-cs 2>/dev/null || true

echo
echo "===== D. timens offsets ====="
cat > /home/ubuntu/work/hook-bundle/config.json <<EOF
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","cat /proc/self/timens_offsets > /tmp/off.txt 2>&1; awk '{print \$1}' /proc/uptime >> /tmp/off.txt"],
             "env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0}},
  "hostname":"t","linux":{"namespaces":[{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"},{"type":"time"}],
                          "cgroupsPath":"/takoyaki-tn",
                          "timeOffsets":{"boottime":{"secs":3600,"nanosecs":0}}} }
EOF
cleanup tn
( cd /home/ubuntu/work/hook-bundle && sudo $BIN create --bundle . tn >/dev/null && sudo $BIN start tn >/dev/null )
sleep 1
echo "timens_offsets (expect '7 3600 0' or similar):"
cat /home/ubuntu/work/hook-bundle/rootfs/tmp/off.txt
sudo $BIN delete --force tn >/dev/null
sudo rmdir /sys/fs/cgroup/takoyaki-tn 2>/dev/null || true

echo
echo "===== E. kernel session keyring ====="
HOST_SESSION=$(awk '/_ses/ {print $1}' /proc/keys | head -1)
cat > /home/ubuntu/work/hook-bundle/config.json <<EOF
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","grep _ses /proc/keys > /tmp/kr.txt"],
             "env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0}},
  "hostname":"k","linux":{"namespaces":[{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"}],
                          "cgroupsPath":"/takoyaki-kr"} }
EOF
cleanup kr
( cd /home/ubuntu/work/hook-bundle && sudo $BIN create --bundle . kr >/dev/null && sudo $BIN start kr >/dev/null )
sleep 1
echo "host session keyring: $HOST_SESSION"
echo "container session keyring: $(cat /home/ubuntu/work/hook-bundle/rootfs/tmp/kr.txt 2>&1 | awk '{print $1}' | head -1)"
sudo $BIN delete --force kr >/dev/null
sudo rmdir /sys/fs/cgroup/takoyaki-kr 2>/dev/null || true

echo
echo "===== F. device cgroup eBPF (cgroup v2) ====="
cat > /home/ubuntu/work/hook-bundle/config.json <<EOF
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","grep cgroup /proc/self/status > /tmp/dev.txt"],
             "env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0}},
  "hostname":"d","linux":{"namespaces":[{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"}],
                          "cgroupsPath":"/takoyaki-dv",
                          "resources":{"devices":[
                            {"allow":false,"access":"rwm"},
                            {"allow":true,"type":"c","major":1,"minor":3,"access":"rwm"}
                          ]}} }
EOF
cleanup dv
( cd /home/ubuntu/work/hook-bundle && sudo $BIN --debug create --bundle . dv 2>&1 | grep -iE "bpf|device" )
sudo $BIN start dv >/dev/null
sleep 1
sudo $BIN delete --force dv >/dev/null
sudo rmdir /sys/fs/cgroup/takoyaki-dv 2>/dev/null || true

echo
echo "===== G. per-mount propagation ====="
mkdir -p /home/ubuntu/work/hook-bundle/rootfs/perm
cat > /home/ubuntu/work/hook-bundle/config.json <<EOF
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","grep ' /perm ' /proc/self/mountinfo > /tmp/prop.txt"],
             "env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0}},
  "hostname":"p","linux":{"namespaces":[{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"}],
                          "cgroupsPath":"/takoyaki-pr"},
  "mounts":[{"destination":"/perm","source":"/tmp","type":"none","options":["rbind","private","ro"]}] }
EOF
cleanup pr
( cd /home/ubuntu/work/hook-bundle && sudo $BIN create --bundle . pr >/dev/null && sudo $BIN start pr >/dev/null )
sleep 1
echo "/perm mountinfo:"
cat /home/ubuntu/work/hook-bundle/rootfs/tmp/prop.txt
sudo $BIN delete --force pr >/dev/null
sudo rmdir /sys/fs/cgroup/takoyaki-pr 2>/dev/null || true

echo
echo "===== H. mount idmap (kernel 5.12+) ====="
# Only meaningful with user ns and idmap-capable kernel.
uname -r
cat > /home/ubuntu/work/hook-bundle/config.json <<EOF
{ "ociVersion":"1.0.0","root":{"path":"rootfs"},
  "process":{"args":["/bin/sh","-c","ls -ln /idmap > /tmp/idm.txt 2>&1"],
             "env":["PATH=/bin"],"cwd":"/","user":{"uid":0,"gid":0}},
  "hostname":"i","linux":{"namespaces":[{"type":"user"},{"type":"pid"},{"type":"mount"},{"type":"uts"},{"type":"ipc"}],
                          "uidMappings":[{"containerID":0,"hostID":100000,"size":65536}],
                          "gidMappings":[{"containerID":0,"hostID":100000,"size":65536}],
                          "cgroupsPath":"/takoyaki-id"},
  "mounts":[{"destination":"/idmap","source":"/tmp","type":"none","options":["bind","rw"],
             "uidMappings":[{"containerID":0,"hostID":100000,"size":1}],
             "gidMappings":[{"containerID":0,"hostID":100000,"size":1}]}] }
EOF
cleanup id
( cd /home/ubuntu/work/hook-bundle && sudo $BIN create --bundle . id 2>&1 | tail -1; sudo $BIN start id 2>&1 | tail -1 )
sleep 1
cat /home/ubuntu/work/hook-bundle/rootfs/tmp/idm.txt 2>&1 || echo "no idmap output"
sudo $BIN delete --force id >/dev/null
sudo rmdir /sys/fs/cgroup/takoyaki-id 2>/dev/null || true

echo
echo "===== DONE ====="
