/* Ask vitalsd for a reading, the same way the launcher will. */
#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/un.h>

int main(int argc, char **argv)
{
    struct sockaddr_un a;
    socklen_t alen;
    const char *req = argc > 1 ? argv[1] : "hr";
    char buf[512];
    int n, s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) { perror("socket"); return 1; }
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    a.sun_path[0] = 0;
    strncpy(a.sun_path + 1, "watchvitals", sizeof a.sun_path - 2);
    alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen("watchvitals"));
    if (connect(s, (struct sockaddr *)&a, alen) < 0) { perror("connect"); return 1; }
    write(s, req, strlen(req));
    write(s, "\n", 1);
    n = read(s, buf, sizeof buf - 1);
    if (n > 0) { buf[n] = 0; printf("%s", buf); }
    close(s);
    return 0;
}
