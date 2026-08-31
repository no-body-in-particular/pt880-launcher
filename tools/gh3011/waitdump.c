/* Snapshot the vendor daemon the moment its algorithm state exists.
 *
 * Rebuilding their initialised state by hand does not converge: every pointer filled in reveals
 * the next one, because what is being rebuilt is an initialiser that already runs perfectly well
 * inside their own daemon. A copy of that daemon's memory has every one of those pointers already
 * correct, which is a snapshot rather than twenty rounds of decompiling.
 *
 * The catch is when to take it. An idle daemon has not allocated anything - dumping one shows the
 * two pointers that matter still null - and their measurement does not start on a command. Walking
 * callers says gh30x_wear_evt_handler calls gh30x_start_func_with_mode, so the trigger is the chip
 * reporting that it is against skin. That makes the useful window a live one, opened by someone
 * putting the watch on, and worth catching automatically rather than by running dd at the right
 * moment.
 *
 * So poll the one pointer that says the algorithm is up, and dump everything when it appears.
 *
 * usage: waitdump <out.bin> [seconds]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>

#define IMAGE     "gh3011_service"
#define CTX_OFF   0x2f640          /* Ghidra 0x3f640, null until the algorithm starts */
#define DUMP_FROM 0x2c000          /* rodata, data and bss - everything writable */
#define DUMP_LEN  0x8000

/* The daemon, by the name of the binary it was started from. */
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
        /* the wrapper execs the real binary, so match either */
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
    int pid, fd, i, secs = argc > 2 ? atoi(argv[2]) : 300;
    unsigned long base;
    char mem[64];
    static unsigned char buf[DUMP_LEN];

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: waitdump <out.bin> [seconds]\n"); return 1; }

    pid = find_daemon();
    if (!pid) { printf("the daemon is not running\n"); return 1; }
    base = load_base(pid);
    if (!base) { printf("pid %d has no mapping of %s\n", pid, IMAGE); return 1; }
    printf("daemon pid %d, image at 0x%lx\n", pid, base);

    snprintf(mem, sizeof mem, "/proc/%d/mem", pid);
    fd = open(mem, O_RDONLY);
    if (fd < 0) { printf("cannot read its memory\n"); return 1; }

    printf("waiting up to %ds for the algorithm to start (put the watch on)...\n", secs);
    for (i = 0; i < secs * 4; i++) {
        unsigned long ctx = 0;
        if (peek(fd, base + CTX_OFF, &ctx, 4) == 0 && ctx) {
            FILE *o;
            printf("\nalgorithm state appeared after %ds: context at 0x%lx\n", i / 4, ctx);
            if (peek(fd, base + DUMP_FROM, buf, DUMP_LEN) != 0) {
                printf("but the dump read failed\n");
                return 1;
            }
            o = fopen(argv[1], "wb");
            if (!o) { printf("cannot write %s\n", argv[1]); return 1; }
            fwrite(buf, 1, DUMP_LEN, o);
            fclose(o);
            printf("wrote %d bytes of live state to %s\n", DUMP_LEN, argv[1]);
            printf("it covers Ghidra 0x%x upward\n", DUMP_FROM + 0x10000);
            return 0;
        }
        if (i % 40 == 0) printf("  %ds...\n", i / 4);
        usleep(250000);
    }
    printf("\nnothing started in %ds - the daemon never saw a wear event\n", secs);
    return 2;
}
