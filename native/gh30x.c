/*
 * Reach the two vendor entry points that have no Java wrapper.
 *
 * The sensor HAL on this watch wedges: a measurement sits at "First flush pending" for its
 * whole window while last= stays frozen, the service is handed nothing and reports zeros, and
 * only a restart of com.ic.work clears it. It has done so three times in two days.
 * docs/vitals.md has the full account.
 *
 * Everything needed to go around it is already on the device, in libICJniUtils.so:
 *
 *     enableSPO2            GLOBAL in .dynsym    starts the chip in SpO2 mode
 *     enablePPG             GLOBAL, and wrapped  starts it in heart rate mode only
 *     disablePPG            GLOBAL, and wrapped  stops it
 *     gh30x_Spo2Start       GLOBAL               what enableSPO2 calls
 *
 * enablePPG and disablePPG have Java_com_ic_jni_ICJniUtils_ wrappers and are callable already.
 * enableSPO2 does not, which is the single reason the launcher has had to ask com.ic.work to
 * start every measurement - and therefore the reason the HAL was in the path at all. Starting
 * the chip ourselves through enablePPG gave heart rate and nothing else: "event ppg 59 ,
 * spo2 0 , weared 1" on every sample of a full window.
 *
 * The symbols are GLOBAL in .dynsym, so dlsym finds them. That is all this file does: open the
 * vendor library and call what is already there. No protocol is reimplemented here, no ioctl is
 * issued by hand, and no guess is made about the hardware - the vendor's own code does the work
 * and the numbers stay theirs.
 *
 * For the record, since it was worked out on the way and would otherwise be lost: those
 * functions drive /dev/gh_tools with _IOW('G', 9, 24) to start and stop, and
 * gh30x_getreportdata reads back with _IOR('G', 11, 24). The node is ioctl-only - its read and
 * write both return -EFAULT, which is why writing "gh30x_ppgStart" to it from the shell failed.
 * Issuing those directly would need the 24-byte argument laid out correctly; calling the
 * vendor's own function is both safer and less work.
 */

#include <dlfcn.h>
#include <jni.h>
#include <stddef.h>

#define VENDOR "libICJniUtils.so"

/* One handle for the life of the process. dlopen on an already-loaded library returns the same
 * one the launcher's System.loadLibrary produced, so this shares its state rather than getting
 * a second copy with its own idea of whether the chip is running. */
static void *vendor(void)
{
    static void *h;
    if (!h) h = dlopen(VENDOR, RTLD_NOW);
    return h;
}

static int call_void_int(const char *sym)
{
    void *h = vendor();
    int (*fn)(void);
    if (!h) return -1;
    fn = (int (*)(void)) dlsym(h, sym);
    if (!fn) return -2;                    /* the symbol is gone: a different build */
    return fn();
}

/*
 * Start the chip in SpO2 mode.
 *
 * Returns the vendor's own result, or -1 if the library will not load, or -2 if the symbol is
 * missing. The caller distinguishes them: -2 means this watch's libICJniUtils differs from the
 * one this was written against, and the old path through com.ic.work is the answer, not a
 * retry.
 */
JNIEXPORT jint JNICALL
Java_org_watchlauncher_Gh30x_enableSpo2(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
    return call_void_int("enableSPO2");
}

JNIEXPORT jint JNICALL
Java_org_watchlauncher_Gh30x_disablePpg(JNIEnv *env, jclass cls)
{
    (void) env; (void) cls;
    return call_void_int("disablePPG");
}

/* Whether the vendor library is here and has what this needs, asked once at startup so the
 * caller can choose a path rather than discovering it mid-measurement. */
JNIEXPORT jboolean JNICALL
Java_org_watchlauncher_Gh30x_available(JNIEnv *env, jclass cls)
{
    void *h = vendor();
    (void) env; (void) cls;
    return (h && dlsym(h, "enableSPO2") && dlsym(h, "disablePPG")) ? JNI_TRUE : JNI_FALSE;
}
