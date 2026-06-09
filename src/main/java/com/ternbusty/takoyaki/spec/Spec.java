package com.ternbusty.takoyaki.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class Spec {
    public String ociVersion = "1.0.0";
    public Root root;
    public Process process;
    public String hostname;
    public Linux linux;
    public List<Mount> mounts;

    public boolean hasNamespace(String type) {
        if (linux == null || linux.namespaces == null) return false;
        for (Namespace ns : linux.namespaces) {
            if (type.equals(ns.type)) return true;
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Root {
        public String path;
        public boolean readonly;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Process {
        public List<String> args = Collections.emptyList();
        public List<String> env;
        public String cwd = "/";
        public Boolean noNewPrivileges;
        public User user = new User();
        public LinuxCapabilities capabilities;
        public List<POSIXRlimit> rlimits;
        public Long umask;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class User {
        public int uid = 0;
        public int gid = 0;
        public List<Integer> additionalGids;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class POSIXRlimit {
        public String type;
        public long hard;
        public long soft;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxCapabilities {
        public List<String> bounding;
        public List<String> effective;
        public List<String> inheritable;
        public List<String> permitted;
        public List<String> ambient;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Namespace {
        public String type;
        public String path;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class IdMapping {
        public long containerID;
        public long hostID;
        public long size;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Mount {
        public String destination;
        public String source;
        public String type;
        public List<String> options;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxResources {
        public LinuxMemory memory;
        public LinuxCpu cpu;
        public LinuxPids pids;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxMemory {
        public Long limit;
        public Long reservation;
        public Long swap;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxCpu {
        public Long shares;
        public Long quota;
        public Long period;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxPids {
        public long limit;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SeccompArg {
        public int index;
        public long value;
        public Long valueTwo;
        public String op;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxSyscall {
        public List<String> names;
        public String action;
        public Long errnoRet;
        public List<SeccompArg> args;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxSeccomp {
        public String defaultAction;
        public Long defaultErrnoRet;
        public List<String> architectures;
        public List<LinuxSyscall> syscalls;
        public List<String> flags;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinuxDevice {
        public String path;
        public String type;
        public Long major;
        public Long minor;
        public Long fileMode;
        public Long uid;
        public Long gid;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Linux {
        public List<Namespace> namespaces;
        public List<IdMapping> uidMappings;
        public List<IdMapping> gidMappings;
        public LinuxResources resources;
        public String cgroupsPath;
        public LinuxSeccomp seccomp;
        public List<LinuxDevice> devices;
        public List<String> maskedPaths;
        public List<String> readonlyPaths;
        public String rootfsPropagation;
    }
}
