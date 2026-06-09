#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <sched.h>
#include <sys/syscall.h>

#ifndef CLONE_NEWUSER
#define CLONE_NEWUSER 0x10000000
#endif
#ifndef CLONE_NEWPID
#define CLONE_NEWPID 0x20000000
#endif
#ifndef CLONE_NEWNET
#define CLONE_NEWNET 0x40000000
#endif
#ifndef CLONE_NEWIPC
#define CLONE_NEWIPC 0x08000000
#endif
#ifndef CLONE_NEWUTS
#define CLONE_NEWUTS 0x04000000
#endif
#ifndef CLONE_NEWNS
#define CLONE_NEWNS 0x00020000
#endif
#ifndef CLONE_NEWCGROUP
#define CLONE_NEWCGROUP 0x02000000
#endif
#ifndef CLONE_NEWTIME
#define CLONE_NEWTIME 0x00000080
#endif
#ifndef CLONE_PARENT
#define CLONE_PARENT 0x00008000
#endif

#define ENV_IS_BOOTSTRAP "_TAKOYAKI_IS_BOOTSTRAP"
#define ENV_SYNCPIPE "_TAKOYAKI_SYNCPIPE"
#define ENV_CLONE_FLAGS "_TAKOYAKI_CLONE_FLAGS"
#define ENV_DEBUG "_TAKOYAKI_BOOTSTRAP_DEBUG"

static int is_init_process = 0;

enum sync_t {
    SYNC_USERMAP_PLS = 0x40,
    SYNC_USERMAP_ACK = 0x41,
    SYNC_GRANDCHILD = 0x44,
    SYNC_CHILD_FINISH = 0x45,
};

static int debug_enabled = 0;

#define DBG(fmt, ...) do { if (debug_enabled) fprintf(stderr, fmt, ##__VA_ARGS__); } while (0)

static int getenv_int(const char *name) {
    char *val = getenv(name);
    if (!val) return -1;
    return atoi(val);
}

static unsigned int parse_hex(const char *str) {
    unsigned int result = 0;
    if (!str) return 0;
    if (str[0] == '0' && (str[1] == 'x' || str[1] == 'X')) str += 2;
    while (*str) {
        char c = *str;
        unsigned int digit;
        if (c >= '0' && c <= '9') digit = c - '0';
        else if (c >= 'a' && c <= 'f') digit = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') digit = c - 'A' + 10;
        else break;
        result = (result << 4) | digit;
        str++;
    }
    return result;
}

static unsigned int getenv_uint_hex(const char *name) {
    char *val = getenv(name);
    if (!val) return 0;
    return parse_hex(val);
}

/* clone3 args struct (subset that we use). The kernel struct is versioned by length. */
struct takoyaki_clone_args {
    unsigned long flags;
    unsigned long pidfd;
    unsigned long child_tid;
    unsigned long parent_tid;
    unsigned long exit_signal;
    unsigned long stack;
    unsigned long stack_size;
    unsigned long tls;
    unsigned long set_tid;
    unsigned long set_tid_size;
    unsigned long cgroup;
};

#ifndef __NR_clone3
# if defined(__aarch64__)
#  define __NR_clone3 435
# else
#  define __NR_clone3 435
# endif
#endif

/* Try clone3 first (provides CLONE_PIDFD and a tidy interface) and fall back to clone
 * if the kernel is too old. The returned pidfd is currently unused but the migration
 * to clone3 is cheap and brings us in line with modern runtimes. */
static pid_t clone_parent(void) {
    struct takoyaki_clone_args ca = {0};
    ca.flags = CLONE_PARENT;
    ca.exit_signal = SIGCHLD;
    long rc = syscall(__NR_clone3, &ca, sizeof(ca));
    if (rc >= 0) return (pid_t) rc;
    if (errno != ENOSYS && errno != EINVAL) {
        fprintf(stderr, "[clone_parent] clone3 failed: %s, falling back to clone\n",
                strerror(errno));
    }
    pid_t pid = syscall(SYS_clone, SIGCHLD | CLONE_PARENT, NULL, NULL, NULL, NULL);
    if (pid < 0) {
        fprintf(stderr, "[clone_parent] clone failed: %s\n", strerror(errno));
    }
    return pid;
}

__attribute__((constructor))
void takoyaki_bootstrap(void) {
    int sync_fd;
    int sync_pipe[2];
    pid_t stage2_pid = -1;
    enum sync_t s;
    unsigned int clone_flags;

    if (!getenv(ENV_IS_BOOTSTRAP)) {
        return;
    }

    debug_enabled = getenv(ENV_DEBUG) != NULL;

    DBG("[stage-1] starting namespace setup\n");

    clone_flags = getenv_uint_hex(ENV_CLONE_FLAGS);
    DBG("[stage-1] clone flags: 0x%x\n", clone_flags);

    sync_fd = getenv_int(ENV_SYNCPIPE);
    if (sync_fd < 0) {
        fprintf(stderr, "[stage-1] missing %s env var\n", ENV_SYNCPIPE);
        exit(1);
    }
    DBG("[stage-1] sync fd: %d\n", sync_fd);

    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sync_pipe) < 0) {
        fprintf(stderr, "[stage-1] socketpair failed: %s\n", strerror(errno));
        exit(1);
    }

    if (clone_flags & CLONE_NEWUSER) {
        DBG("[stage-1] unshare(CLONE_NEWUSER)\n");
        if (unshare(CLONE_NEWUSER) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWUSER) failed: %s\n", strerror(errno));
            exit(1);
        }
        if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) < 0) {
            fprintf(stderr, "[stage-1] prctl(PR_SET_DUMPABLE,1) failed: %s\n", strerror(errno));
            exit(1);
        }
        s = SYNC_USERMAP_PLS;
        if (write(sync_fd, &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-1] write SYNC_USERMAP_PLS failed: %s\n", strerror(errno));
            exit(1);
        }
        pid_t my_pid = getpid();
        if (write(sync_fd, &my_pid, sizeof(my_pid)) != sizeof(my_pid)) {
            fprintf(stderr, "[stage-1] write pid failed: %s\n", strerror(errno));
            exit(1);
        }
        if (read(sync_fd, &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-1] read SYNC_USERMAP_ACK failed: %s\n", strerror(errno));
            exit(1);
        }
        if (s != SYNC_USERMAP_ACK) {
            fprintf(stderr, "[stage-1] expected SYNC_USERMAP_ACK, got 0x%x\n", s);
            exit(1);
        }
        if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) < 0) {
            fprintf(stderr, "[stage-1] prctl(PR_SET_DUMPABLE,0) failed: %s\n", strerror(errno));
            exit(1);
        }
        if (setuid(0) < 0) {
            fprintf(stderr, "[stage-1] setuid(0) failed: %s\n", strerror(errno));
            exit(1);
        }
        if (setgid(0) < 0) {
            fprintf(stderr, "[stage-1] setgid(0) failed: %s\n", strerror(errno));
            exit(1);
        }
        DBG("[stage-1] now root in user namespace\n");
    }

    /* cgroup namespace must be unshared BEFORE mount namespace so that the new mount
     * namespace observes the cgroup namespace's view of /sys/fs/cgroup. */
    if (clone_flags & CLONE_NEWCGROUP) {
        DBG("[stage-1] unshare(CLONE_NEWCGROUP)\n");
        if (unshare(CLONE_NEWCGROUP) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWCGROUP) failed: %s\n", strerror(errno));
            exit(1);
        }
    }
    if (clone_flags & CLONE_NEWNS) {
        DBG("[stage-1] unshare(CLONE_NEWNS)\n");
        if (unshare(CLONE_NEWNS) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWNS) failed: %s\n", strerror(errno));
            exit(1);
        }
    }
    if (clone_flags & CLONE_NEWNET) {
        DBG("[stage-1] unshare(CLONE_NEWNET)\n");
        if (unshare(CLONE_NEWNET) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWNET) failed: %s\n", strerror(errno));
            exit(1);
        }
    }
    if (clone_flags & CLONE_NEWUTS) {
        DBG("[stage-1] unshare(CLONE_NEWUTS)\n");
        if (unshare(CLONE_NEWUTS) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWUTS) failed: %s\n", strerror(errno));
            exit(1);
        }
    }
    if (clone_flags & CLONE_NEWIPC) {
        DBG("[stage-1] unshare(CLONE_NEWIPC)\n");
        if (unshare(CLONE_NEWIPC) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWIPC) failed: %s\n", strerror(errno));
            exit(1);
        }
    }
    /* time namespace must be unshared BEFORE pid namespace because once the new
     * pid_for_children namespace is set the kernel won't accept time NS changes. */
    if (clone_flags & CLONE_NEWTIME) {
        DBG("[stage-1] unshare(CLONE_NEWTIME)\n");
        if (unshare(CLONE_NEWTIME) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWTIME) failed: %s\n", strerror(errno));
            exit(1);
        }
    }
    if (clone_flags & CLONE_NEWPID) {
        DBG("[stage-1] unshare(CLONE_NEWPID)\n");
        if (unshare(CLONE_NEWPID) < 0) {
            fprintf(stderr, "[stage-1] unshare(CLONE_NEWPID) failed: %s\n", strerror(errno));
            exit(1);
        }
    }

    DBG("[stage-1] cloning stage-2 with CLONE_PARENT\n");
    stage2_pid = clone_parent();

    if (stage2_pid < 0) {
        fprintf(stderr, "[stage-1] clone failed: %s\n", strerror(errno));
        exit(1);
    }

    if (stage2_pid == 0) {
        close(sync_pipe[1]);
        close(sync_fd);
        DBG("[stage-2] started, pid=%d\n", getpid());
        if (read(sync_pipe[0], &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-2] read SYNC_GRANDCHILD failed: %s\n", strerror(errno));
            _exit(1);
        }
        if (s != SYNC_GRANDCHILD) {
            fprintf(stderr, "[stage-2] expected SYNC_GRANDCHILD, got 0x%x\n", s);
            _exit(1);
        }
        if (setsid() < 0) {
            fprintf(stderr, "[stage-2] setsid failed: %s\n", strerror(errno));
            _exit(1);
        }
        s = SYNC_CHILD_FINISH;
        if (write(sync_pipe[0], &s, sizeof(s)) != sizeof(s)) {
            fprintf(stderr, "[stage-2] write SYNC_CHILD_FINISH failed: %s\n", strerror(errno));
            _exit(1);
        }
        close(sync_pipe[0]);

        /* Unset bootstrap-related env so the new process runs Java runtime fresh
         * as the init process (detected via args[0] == "__init__"). */
        unsetenv(ENV_IS_BOOTSTRAP);
        unsetenv(ENV_SYNCPIPE);
        unsetenv(ENV_CLONE_FLAGS);

        DBG("[stage-2] execve(/proc/self/exe __init__) to start fresh runtime\n");
        char *argv[] = { "takoyaki", "__init__", NULL };
        execv("/proc/self/exe", argv);
        fprintf(stderr, "[stage-2] execv failed: %s\n", strerror(errno));
        _exit(1);
    }

    close(sync_pipe[0]);
    DBG("[stage-1] forked stage-2 pid=%d\n", stage2_pid);

    if (write(sync_fd, &stage2_pid, sizeof(stage2_pid)) != sizeof(stage2_pid)) {
        fprintf(stderr, "[stage-1] write stage-2 pid failed: %s\n", strerror(errno));
        exit(1);
    }

    s = SYNC_GRANDCHILD;
    if (write(sync_pipe[1], &s, sizeof(s)) != sizeof(s)) {
        fprintf(stderr, "[stage-1] write SYNC_GRANDCHILD failed: %s\n", strerror(errno));
        exit(1);
    }
    if (read(sync_pipe[1], &s, sizeof(s)) != sizeof(s)) {
        fprintf(stderr, "[stage-1] read SYNC_CHILD_FINISH failed: %s\n", strerror(errno));
        exit(1);
    }
    if (s != SYNC_CHILD_FINISH) {
        fprintf(stderr, "[stage-1] expected SYNC_CHILD_FINISH, got 0x%x\n", s);
        exit(1);
    }

    close(sync_pipe[1]);
    close(sync_fd);
    DBG("[stage-1] exiting; stage-2 continues as init\n");
    _exit(0);
}

__attribute__((visibility("default")))
int takoyaki_is_init_process(void) {
    return is_init_process;
}
