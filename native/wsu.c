/*
 * wsu -- the smallest thing that gives an app on this watch a root shell.
 *
 * The adbd patch in this repo makes `adb shell` uid 0, but an app is not a
 * child of adbd: it is forked from zygote, and zygote drops to the app's uid
 * before the app's first instruction runs. Nothing an app can call brings that
 * back.
 *
 * What makes this work is two properties of this particular build:
 *
 *   - Android 4.4's zygote sets the app's capabilities with capset() and never
 *     calls PR_CAPBSET_DROP, so an app process still has the full capability
 *     *bounding* set. On execve the file's permitted set is masked by the
 *     bounding set -- a full bounding set means a setuid-root binary gets
 *     everything.
 *   - SELinux is Disabled on this device, and /system is mounted without
 *     `nosuid`, so the setuid bit is honoured and nothing else objects.
 *
 * Installed 06755 root:root in /system/xbin by install-root-helper.sh. It is
 * an unconditional root escalation for anything that can exec it, which on
 * this device is every app -- that is the point, and it is why it belongs on a
 * tracker you have taken ownership of and nowhere else.
 *
 * With no arguments it execs a shell. With arguments it execs them directly,
 * so `wsu id` behaves the way `su -c id` would.
 *
 * Build with native/build_wsu.sh (NDK r21e, armeabi-v7a, API 19).
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <grp.h>

int main(int argc, char **argv)
{
    /*
     * Order matters: setgroups and setgid must happen while we still have
     * CAP_SETGID, which setuid(0) would not take away here but does in the
     * general case. Doing it in the wrong order is the classic way to leave a
     * process with root's uid and the caller's groups.
     */
    if (setgroups(0, NULL) != 0 && errno != EPERM) {
        fprintf(stderr, "wsu: setgroups: %s\n", strerror(errno));
    }
    if (setgid(0) != 0) {
        fprintf(stderr, "wsu: setgid: %s\n", strerror(errno));
        return 1;
    }
    if (setuid(0) != 0) {
        fprintf(stderr, "wsu: setuid: %s\n", strerror(errno));
        return 1;
    }

    /* A shell that inherits the app's environment inherits its ANDROID_DATA
     * and its cwd inside /data/data, which it cannot read. Give it somewhere
     * sane to stand and let it find the rest. */
    if (chdir("/") != 0) {
        /* Not fatal: the shell will still run, just from wherever we were. */
    }
    setenv("HOME", "/data", 1);
    setenv("PATH", "/system/xbin:/system/bin:/sbin:/vendor/bin", 1);

    if (argc > 1) {
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "wsu: %s: %s\n", argv[1], strerror(errno));
        return 127;
    }

    /* mksh is what this build ships as its shell; /system/bin/sh is a symlink
     * to it. Fall back to the busybox this repo installs if it is not. */
    execl("/system/bin/sh", "sh", (char *) NULL);
    execl("/system/xbin/busybox", "sh", (char *) NULL);
    fprintf(stderr, "wsu: no shell: %s\n", strerror(errno));
    return 127;
}
