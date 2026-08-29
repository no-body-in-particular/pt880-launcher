/*
 * Drive the heart rate sensor directly, through the kernel.
 *
 * The sensor HAL on this watch wedges: a measurement sits at "First flush pending" for its
 * whole window while last= stays frozen, the vendor service is handed nothing and reports
 * zeros, and only restarting com.ic.work clears it. docs/vitals.md has the account. Everything
 * below exists to take that component out of the path rather than recover from it.
 *
 * THE PROTOCOL
 *
 * /dev/gh_tools is ioctl-only - its read and write both return -EFAULT, which is why writing
 * "gh30x_ppgStart" to it from a shell fails - and it is crwxrwxrwx, so no root is needed. Two
 * commands, both carrying a 24 byte buffer:
 *
 *     _IOW('G',  9, 24)   0x40184709   start or stop, opcode in the first word
 *     _IOR('G', 11, 24)   0x8018470b   read the current reading back
 *
 * The opcodes were read out of libICJniUtils.so, which is the vendor's own user-space library:
 *
 *     4   gh30x_ppgStart     heart rate only
 *     5   gh30x_Spo2Start    heart rate and SpO2
 *     6   gh30x_ppgStop
 *
 * Each of those functions does the same three things - open the node, memset a 24 byte buffer,
 * write its opcode to offset 0, ioctl - so the buffer is zeroed apart from that word:
 *
 *     movs r2, #24        blx memset        movs r3, #5      str r3, [sp, #0]
 *     ldr  r1, [pc, #16]  ; 0x40184709      blx ioctl
 *
 * The report has no opcode: it is memset to zero and read into. Six words, and the library's own
 * log line names five of them - "is wared %d , ppg %d , spo2 %d , bph %d , bpl %d" - which is
 * where the field order below comes from. It is not guessed at in the reading: report() hands
 * all six words to Java and the caller decides, so a layout that turns out different is a change
 * there rather than here.
 *
 * WHY DIRECTLY RATHER THAN THROUGH THE LIBRARY
 *
 * The library route works and shipped first: dlsym for enableSPO2, which is GLOBAL in .dynsym
 * and simply has no Java_ wrapper, which is the single reason com.ic.work was ever needed to
 * start a measurement. It is kept below as a fallback, because it is the vendor's own tested
 * sequence and a good thing to fall back to.
 *
 * Doing it directly removes the last dependency on their user-space code, and gets the report
 * as well - blood pressure included, from the same read as the pulse, rather than polling an
 * algorithm through a separate entry point while it is still running.
 */

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define NODE            "/dev/gh_tools"
#define GH_ARG_BYTES    24
#define GH_IOC_CMD      0x40184709u     /* _IOW('G',  9, 24) */
#define GH_IOC_REPORT   0x8018470bu     /* _IOR('G', 11, 24) */

#define GH_CMD_PPG      4
#define GH_CMD_SPO2     5
#define GH_CMD_STOP     6

#define VENDOR "libICJniUtils.so"

/* Send one opcode. Returns the driver's own result, or -errno if the node will not open. */
static int gh_cmd(unsigned int opcode)
{
    unsigned char buf[GH_ARG_BYTES];
    int fd, rc;

    fd = open(NODE, O_RDONLY);
    if (fd < 0) return -errno ? -errno : -1;

    memset(buf, 0, sizeof buf);
    memcpy(buf, &opcode, sizeof opcode);
    rc = ioctl(fd, GH_IOC_CMD, buf);
    close(fd);
    return rc;
}

static void *vendor(void)
{
    static void *h;
    if (!h) h = dlopen(VENDOR, RTLD_NOW);
    return h;
}

static int vendor_call(const char *sym)
{
    void *h = vendor();
    int (*fn)(void);
    if (!h) return -1;
    fn = (int (*)(void)) dlsym(h, sym);
    if (!fn) return -2;
    return fn();
}

/*
 * Start in SpO2 mode.
 *
 * The ioctl first, and the library only if that fails: the direct route needs nothing of the
 * vendor's user space, but their function is the sequence known to work, so it is worth having
 * behind it rather than failing outright on a build whose driver differs.
 */
JNIEXPORT jint JNICALL
Java_org_watchlauncher_Gh30x_enableSpo2(JNIEnv *env, jclass cls)
{
    int rc;
    (void) env; (void) cls;

    /*
     * The library first, and the raw ioctl only if it is missing.
     *
     * Both start the chip, and the raw route needs nothing of the vendor's user space - but
     * blood pressure is not in the driver. Its report reads
     *
     *     0 61 87 0 0 1     status, ppg, spo2, bph, bpl, ?
     *
     * with a pulse and an SpO2 that match the input device and a pressure of zero, on a worn
     * wrist during a good measurement. The pair is computed above the driver, in their
     * algorithm, from a waveform that never leaves the chip - so starting by ioctl means that
     * algorithm never runs and getHighBloodPressure has nothing to return.
     *
     * Independence from their library would cost blood pressure entirely, which is too high a
     * price when com.ic.work and the HAL - the parts that actually wedge - are avoided either
     * way. The ioctl stays as the fallback, and the numbers stay recorded above.
     */
    rc = vendor_call("enableSPO2");
    if (rc >= 0) return rc;
    return gh_cmd(GH_CMD_SPO2);
}

JNIEXPORT jint JNICALL
Java_org_watchlauncher_Gh30x_disablePpg(JNIEnv *env, jclass cls)
{
    int rc;
    (void) env; (void) cls;

    /* Stopped the same way it was started, so their library's own state follows the chip. */
    rc = vendor_call("disablePPG");
    if (rc >= 0) return rc;
    return gh_cmd(GH_CMD_STOP);
}

/*
 * The current reading: six words, straight out of the driver.
 *
 * Returns null when the node will not open or the ioctl is refused, which the caller reads as
 * "ask the other way" rather than as a measurement of zero. The words are handed over as they
 * came; naming them is the caller's business.
 */
JNIEXPORT jintArray JNICALL
Java_org_watchlauncher_Gh30x_report(JNIEnv *env, jclass cls)
{
    unsigned char buf[GH_ARG_BYTES];
    jintArray out;
    jint words[GH_ARG_BYTES / 4];
    int fd, rc;
    (void) cls;

    fd = open(NODE, O_RDONLY);
    if (fd < 0) return NULL;

    memset(buf, 0, sizeof buf);
    rc = ioctl(fd, GH_IOC_REPORT, buf);
    close(fd);
    if (rc < 0) return NULL;

    memcpy(words, buf, sizeof words);
    out = (*env)->NewIntArray(env, GH_ARG_BYTES / 4);
    if (!out) return NULL;
    (*env)->SetIntArrayRegion(env, out, 0, GH_ARG_BYTES / 4, words);
    return out;
}

/* Whether the node is there at all. The library is not required for the direct path. */
JNIEXPORT jboolean JNICALL
Java_org_watchlauncher_Gh30x_available(JNIEnv *env, jclass cls)
{
    int fd;
    (void) env; (void) cls;

    fd = open(NODE, O_RDONLY);
    if (fd >= 0) { close(fd); return JNI_TRUE; }
    return (vendor() && dlsym(vendor(), "enableSPO2")) ? JNI_TRUE : JNI_FALSE;
}
