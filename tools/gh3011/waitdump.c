/* Snapshot the vendor daemon while each kind of measurement is configured.
 *
 * Rebuilding their initialised state by hand does not converge: every pointer filled in reveals
 * the next, because what is being rebuilt is an initialiser that already runs correctly inside
 * their own daemon. A copy of that daemon memory has all of those pointers already right, which
 * is one snapshot rather than twenty rounds of decompiling.
 *
 * The awkward part is when to copy. gh30x_fifo_evt_handler branches on the mode and hands each
 * routine the config at 0x3d5ac:
 *
 *     mode 2   heart rate    FUN_0001b7c0
 *     mode 3   hrv           FUN_00018d78
 *     mode 7   saturation    FUN_00018f38
 *
 * so that config being non-null is what says a real measurement is set up. An idle daemon has it
 * zero. So does one doing wear detection, which is what the first snapshot here caught - the
 * context existed, the config did not, and it was no use to either routine.
 *
 * Waiting for one config and stopping gets whichever mode happens first. Watching the mode byte
 * and keeping one snapshot per value collects them all from a single night instead, with nobody
 * needing to be awake to catch the right moment.
 *
 * usage: waitdump <out.bin> [seconds]     writes <out.bin>.mode2, .mode7, and so on
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>

#define IMAGE     "gh3011_service"
#define CFG_OFF   0x2d5ac          /* Ghidra 0x3d5ac: the config every routine takes */
#define MODE_OFF  0x2ec4a          /* Ghidra 0x3ec4a: which measurement it is for */
#define DUMP_FROM 0x2c000          /* rodata, data and bss - everything writable */
#define DUMP_LEN  0x8000

static int find_daemon(void)
{
    DIR *d = opendir("/proc");
    struct dirent *e;
    int found = 0;

    if (!d) return 0;
    while (!found && (e = readdir(d))) {
        char path[256], buf[256];
        int fd, n;
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        snprintf(path, sizeof path, "/proc/%s/cmdline", e->d_name);
        fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        n = read(fd, buf, sizeof buf - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = 0;
        if (strstr(buf, IMAGE)) found = atoi(e->d_name);
    }
    closedir(d);
    return found;
}

/* Where the image landed. The first mapping of the file is the load base. */
static unsigned long load_base(int pid)
{
    char path[64], line[512];
    FILE *f;
    unsigned long best = 0;

    snprintf(path, sizeof path, "/proc/%d/maps", pid);
    f = fopen(path, "r");
    if (!f) return 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long lo;
        if (!strstr(line, IMAGE)) continue;
        lo = strtoul(line, NULL, 16);
        if (!best || lo < best) best = lo;
    }
    fclose(f);
    return best;
}

static int peek(int fd, unsigned long addr, void *out, int len)
{
    if (lseek(fd, (off_t) addr, SEEK_SET) == (off_t) -1) return -1;
    return read(fd, out, len) == len ? 0 : -1;
}

int main(int argc, char **argv)
{
    int pid, fd, i, have = 0, secs = argc > 2 ? atoi(argv[2]) : 300;
    unsigned long base;
    char mem[64];
    unsigned char seen[256];
    static unsigned char buf[DUMP_LEN];

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: waitdump <out.bin> [seconds]\n"); return 1; }

    pid = find_daemon();
    if (!pid) { printf("the daemon is not running\n"); return 1; }
    base = load_base(pid);
    if (!base) { printf("pid %d has no mapping of %s\n", pid, IMAGE); return 1; }

    snprintf(mem, sizeof mem, "/proc/%d/mem", pid);
    fd = open(mem, O_RDONLY);
    if (fd < 0) { printf("cannot read the memory of pid %d\n", pid); return 1; }

    printf("daemon pid %d, image at 0x%lx\n", pid, base);
    printf("watching up to %ds; each mode is saved once, as it appears\n", secs);

    memset(seen, 0, sizeof seen);
    for (i = 0; i < secs * 4; i++) {
        unsigned long cfg = 0;
        unsigned char mode = 0;

        if (peek(fd, base + CFG_OFF, &cfg, 4) == 0 && cfg &&
            peek(fd, base + MODE_OFF, &mode, 1) == 0 && !seen[mode]) {
            char name[300];
            FILE *o;

            seen[mode] = 1;
            snprintf(name, sizeof name, "%s.mode%u", argv[1], mode);
            if (peek(fd, base + DUMP_FROM, buf, DUMP_LEN) != 0) {
                printf("  mode %u appeared but its memory could not be read\n", mode);
                continue;
            }
            o = fopen(name, "wb");
            if (!o) { printf("  cannot write %s\n", name); continue; }
            fwrite(buf, 1, DUMP_LEN, o);
            fclose(o);
            have++;
            printf("\n[%ds] mode %u configured, config at 0x%lx -> %s\n", i / 4, mode, cfg, name);
        }
        if (i % 240 == 0) printf("  %ds, %d modes so far...\n", i / 4, have);
        usleep(250000);
    }

    printf("\ndone: %d distinct modes captured\n", have);
    return have ? 0 : 2;
}
