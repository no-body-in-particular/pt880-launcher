/* Does the confidence number mean anything?
 *
 * spectral_purity was taken from the vendor firmware and is meant to say how much of the pulse
 * band is the pulse. That claim is easy to make and easy to get wrong - a ratio that comes out
 * near the same value whatever it is shown would look like a confidence and carry none - so it is
 * worth checking against signals whose answer is known before trusting it on a wrist.
 *
 * Four cases, in the order they should score:
 *
 *     a clean pulse                     everything in one bin, so high
 *     a pulse with noise on top         still peaked, so lower but not low
 *     noise alone                       spread across the band, so low
 *     a flat line                       nothing to judge, so zero
 *
 * It includes ppgd.c rather than copying the function, because a test of a copy is a test of the
 * copy. main is renamed out of the way.
 */
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

#define main ppgd_main
#include "ppgd.c"
#undef main

#define N   4000
#define FS  100.0

static unsigned rng = 12345;
static double noise(void)
{
    rng = rng * 1103515245u + 12345u;
    return ((double)((rng >> 16) & 0x7fff) / 16383.5) - 1.0;   /* -1 .. 1 */
}

static void fill(unsigned int *x, double bpm, double amp, double noise_amp)
{
    int i;
    for (i = 0; i < N; i++) {
        double t = i / FS;
        double v = DARK_CODE + 40000.0;
        if (bpm > 0) v += amp * sin(2.0 * 3.14159265358979 * (bpm / 60.0) * t);
        v += noise_amp * noise();
        x[i] = (unsigned int)(v < 0 ? 0 : v);
    }
}

int main(void)
{
    static unsigned int x[N];
    struct { const char *what; double bpm, amp, noise; } cases[] = {
        { "a clean pulse at 72",        72.0, 2000.0,     0.0 },
        { "a pulse under equal noise",  72.0, 2000.0,  2000.0 },
        { "noise alone",                 0.0,    0.0,  2000.0 },
        { "a flat line",                 0.0,    0.0,     0.0 },
    };
    int i;
    double prev = 2.0;
    int ordered = 1;

    printf("purity of known signals, %d samples at %.0f Hz\n\n", N, FS);
    for (i = 0; i < (int)(sizeof cases / sizeof cases[0]); i++) {
        double p, dc = 0.0;
        int k;
        fill(x, cases[i].bpm, cases[i].amp, cases[i].noise);
        for (k = 0; k < N; k++) dc += x[k];
        dc /= N;
        p = spectral_purity(x, N, FS, 72.0);
        printf("  %-26s purity %.3f   level %.0f %s\n", cases[i].what, p, dc - DARK_CODE,
               level_usable(dc - DARK_CODE) ? "usable" : "OUT OF RANGE");
        if (p > prev + 1e-9) ordered = 0;
        prev = p;
    }

    printf("\n%s\n", ordered
           ? "ordered as it should be: cleaner signals score higher"
           : "NOT ordered - a noisier signal scored higher than a cleaner one, so this number"
             " is not measuring what it claims and should not be reported");

    /* And the level gate, which is the half that actually refuses. */
    printf("\nlevel gate:\n");
    {
        double probe[] = { 0.0, 1999.0, 2000.0, 40000.0, 125000.0, 125001.0, 3000000.0 };
        int k;
        for (k = 0; k < (int)(sizeof probe / sizeof probe[0]); k++)
            printf("  level %10.0f  %s\n", probe[k],
                   level_usable(probe[k]) ? "usable" : "refused");
    }
    return ordered ? 0 : 1;
}
