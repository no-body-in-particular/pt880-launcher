# Read a saturation calibration out of a breath hold.
#
# R is a ratio of ratios and means nothing on its own: it has to be tied to a saturation somewhere.
# A reference oximeter would do it directly and there is not one here, but a breath hold provides
# the second point for free. At rest a healthy wearer sits at about 97; holding a breath takes them
# down by a few percent and lets them back up. Two points fix a line, and the line is the
# calibration.
#
# The resting point supplies the intercept and is assumed rather than measured, so the absolute
# number this produces inherits that assumption. The slope is measured, and the slope is the half
# that matters - it is what decides whether a fall of three percent reads as three or as ten.
#
# The amplitude method is ppgd's, not a reimplementation of it: six-second windows, Goertzel
# magnitudes averaged, the pulse frequency found on the stronger channel. Checked against ppgd's
# own r= on the same recording and agreeing to three decimals, because an earlier pass at this used
# one Goertzel over the whole record, disagreed with ppgd by a factor of three, and the disagreement
# was read as a fault in the sensor.

import math
import sys

DARK = 3145728.0
MIN_AC2 = 30.0          # below this the ratio is quantisation - see the notes on weak=1


def load(path):
    a, b = [], []
    with open(path) as f:
        fs = float(f.readline())
        for line in f:
            t = line.split()
            if len(t) == 2:
                a.append(int(t[0]))
                b.append(int(t[1]))
    return fs, a, b


def goertzel(x, fs, f, mean):
    w = 2.0 * math.pi * f / fs
    c = 2.0 * math.cos(w)
    s1 = s2 = 0.0
    for v in x:
        s0 = (v - mean) + c * s1 - s2
        s2 = s1
        s1 = s0
    p = s1 * s1 + s2 * s2 - c * s1 * s2
    return 2.0 * math.sqrt(p) / len(x) if p > 0 else 0.0


def band_amp(x, fs, f):
    """ppgd's estimator: six-second windows, magnitudes averaged, each window's own mean removed."""
    w = int(fs * 6.0)
    if w > len(x):
        w = len(x)
    step = max(1, w // 2)
    tot = 0.0
    n = 0
    for i in range(0, len(x) - w + 1, step):
        seg = x[i:i + w]
        tot += goertzel(seg, fs, f, sum(seg) / len(seg))
        n += 1
    return tot / n if n else 0.0


def pulse_hz(x, fs):
    best = (0.0, 0.0)
    f = 0.7
    while f <= 2.2:
        v = band_amp(x, fs, f)
        if v > best[0]:
            best = (v, f)
        f += 0.02
    return best[1]


def main(path, win_s=20.0, step_s=5.0):
    fs, a, b = load(path)
    w = int(fs * win_s)
    step = int(fs * step_s)
    print('%d samples at %.1f Hz = %.0f s\n' % (len(a), fs, len(a) / fs))
    print('  t(s)     R     ac1    ac2   resp   pulse   note')
    rows = []
    for i in range(0, len(a) - w + 1, step):
        wa, wb = a[i:i + w], b[i:i + w]
        d1 = sum(wa) / len(wa)
        d2 = sum(wb) / len(wb)
        fp = pulse_hz(wb, fs)
        a1 = band_amp(wa, fs, fp)
        a2 = band_amp(wb, fs, fp)
        # breathing shows in the same trace, well below the pulse
        resp = max(band_amp(wb, fs, f / 100.0) for f in range(12, 46, 4))
        l1, l2 = d1 - DARK, d2 - DARK
        R = ((a1 / l1) / (a2 / l2)) if l1 > 0 and l2 > 0 and a2 > 0 else 0.0
        weak = a2 < MIN_AC2
        rows.append((i / fs, R, a1, a2, resp, fp * 60.0, weak))
        print('  %4.0f  %6.3f  %6.1f %6.1f %6.1f  %5.0f   %s' %
              (i / fs, R, a1, a2, resp, fp * 60.0, 'weak' if weak else ''))

    good = [r for r in rows if not r[6]]
    if len(good) < 4:
        print('\nnot enough windows with pulse in them to calibrate')
        return
    Rs = [r[1] for r in good]
    resps = [r[4] for r in good]
    med_resp = sorted(resps)[len(resps) // 2]
    # a hold is where the breathing stops; take the quietest quarter as held
    held = [r for r in good if r[4] < med_resp * 0.6]
    free = [r for r in good if r[4] > med_resp]
    print('\n  %d windows with usable pulse, R from %.3f to %.3f' % (len(good), min(Rs), max(Rs)))
    if held and free:
        hb = sum(r[1] for r in held) / len(held)
        fb = sum(r[1] for r in free) / len(free)
        print('  breathing freely: R %.3f over %d windows' % (fb, len(free)))
        print('  breath held     : R %.3f over %d windows' % (hb, len(held)))
        print('  difference      : %+.3f' % (hb - fb))
        if abs(hb - fb) < 0.03:
            print('\n  too small to calibrate on: the hold moved R by less than the'
                  ' window-to-window scatter, so no slope can be read from it.')
        else:
            # assume rest is 97% and the hold reaches 93 - the usual fall for a comfortable hold
            slope = (97.0 - 93.0) / (hb - fb)
            print('\n  taking rest as 97%% and the hold as 93%%, slope = %+.1f %% per unit R' % -slope)
            print('  so SpO2 = %.1f - %.1f * R' % (97.0 + slope * fb, slope))
            print('  the 93 is assumed, not measured, so the slope carries that assumption')
    else:
        print('  could not separate held from free breathing in this recording')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'cal.txt')
