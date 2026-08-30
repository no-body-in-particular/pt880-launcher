#!/usr/bin/env python3
"""Take a vitals reading from the watch: heart rate, HRV, SpO2, and a blood pressure estimate.

    python vitals.py hr            heart rate and beat-to-beat variability
    python vitals.py spo2          adds the red/IR ratio, when the measurement ran in SpO2 mode
    python vitals.py bp            adds the pressure estimate
    python vitals.py hr --keep w   also writes the raw waveform to w.csv

How it gets the waveform
------------------------
The sensor's FIFO is register 0xaaaa, but it cannot simply be read: the daemon drains it, and a
reader competing for it gets the same stale register back 99% of the time - measured, not
assumed. So the daemon is briefly wrapped with an ioctl interposer, the measurement is triggered
the way the app triggers it, and the waveform is read out of the daemon's own i2c traffic. The
wrapper is removed again by the capture script whatever happens.

Two conditions gate every capture, both of which produce a silent empty result if ignored:
the watch must be on a wrist, and the sensor only measures once per boot - so this reboots
first unless told not to.

Blood pressure
--------------
Read docs/vitals.md before believing any number this prints. The watch's own "blood pressure" is
a threshold cascade over heart rate that emits canned values; it is not a measurement. What this
computes instead is a real regression over real pulse-shape features - but a regression with no
calibration behind it is still just a plausible-looking number. Take cuff readings with
--calibrate, and after three or more pairs the fit is yours rather than a guess.
"""

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CALIB = os.path.join(HERE, "bp_calibration.json")
REMOTE = "/data/local/tmp"


# ---------------------------------------------------------------- device


def adb(serial, *args, timeout=180):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env)


def pick_device(serial):
    out = adb(None, "devices").stdout.splitlines()
    devs = [l.split()[0] for l in out[1:] if l.strip() and l.split()[-1] == "device"]
    if serial:
        return serial
    if not devs:
        sys.exit("no adb device. connect USB, or 'adb connect <ip>:5555'")
    if len(devs) > 1:
        sys.exit("several devices: %s - pass --serial" % ", ".join(devs))
    return devs[0]


def reboot(serial):
    print("rebooting (the sensor only measures once per boot)")
    adb(serial, "reboot")
    for _ in range(40):
        time.sleep(5)
        if adb(serial, "shell", "getprop sys.boot_completed").stdout.strip() == "1":
            time.sleep(12)          # let the sensor stack settle
            print("  up")
            return True
    sys.exit("the watch did not come back")


def capture(serial, secs):
    """Run the on-device capture and return the daemon's i2c log."""
    for f in ("i2ctap.so", "wristcap.sh", "sensorstart", "evread"):
        local = os.path.join(HERE, f)
        if os.path.exists(local):
            adb(serial, "push", local, "%s/%s" % (REMOTE, f))
    print("measuring for %ds - keep the wrist still" % secs)
    r = adb(serial, "shell", "sh %s/wristcap.sh %d" % (REMOTE, secs), timeout=secs + 180)
    reported = [l for l in r.stdout.splitlines() if "hr=" in l]
    log = adb(serial, "shell", "cat %s/i2c.log" % REMOTE, timeout=180).stdout
    return log, reported


# ---------------------------------------------------------------- waveform


def decode(log):
    """Pull the 24-bit interleaved samples out of the tapped FIFO reads."""
    ch1, ch2 = [], []
    for line in log.splitlines():
        if not line.startswith("R aaaa"):
            continue
        b = [int(x, 16) for x in line.split()[2:] if re.fullmatch(r"[0-9a-f]{2}", x)]
        for i in range(0, len(b) - 5, 6):
            ch1.append((b[i] << 16) | (b[i + 1] << 8) | b[i + 2])
            ch2.append((b[i + 3] << 16) | (b[i + 4] << 8) | b[i + 5])
    return ch1, ch2


def movavg(x, w):
    out = []
    half = w // 2
    for i in range(len(x)):
        lo = max(0, i - half)
        hi = min(len(x), i + half + 1)
        out.append(sum(x[lo:hi]) / (hi - lo))
    return out


def detrend(x, fs):
    """Remove the DC level and slow drift, and turn the pulse the right way up.

    The sign matters. This is a reflective sensor: more blood in the tissue absorbs more light,
    so the count *falls* at systole and the raw trace is upside down. Heart rate does not care -
    the spacing is the same either way - but every shape feature does, and reading it as-read
    gives a pulse that rises in 578 ms and falls in 361 ms, which no artery does.

    Inverted, the same recording rises in 170 ms and decays over 1012 ms: a fast systolic
    upstroke and a long diastolic decay, which is what a pulse actually looks like. Measured on
    two captures at three baseline widths; the as-read orientation failed all six.
    """
    base = movavg(x, int(fs))
    d = [base[i] - x[i] for i in range(len(x))]
    return movavg(d, 5)


def quiet_windows(d, fs, seconds=8):
    """Split into windows and return them cleanest-first.

    A real capture contains motion excursions of hundreds of counts on top of a pulse of tens,
    so picking calm stretches matters more than any amount of filtering.

    Eight seconds, not twelve. A wrist is rarely still for twelve, and a window only has to be
    calm for long enough to hold four or five beats. Measured on two real captures: with twelve
    second windows an ordinary at-the-desk recording yields nothing usable at all, while eight
    gives 77 bpm against the 76 the watch itself reported. On a still recording the two agree,
    so the shorter window costs nothing and rescues the moving case.

    Windows overlap by three quarters so a calm patch is not missed for straddling a boundary.
    """
    n = int(fs * seconds)
    wins = []
    for s in range(0, max(1, len(d) - n), max(1, n // 4)):
        seg = d[s:s + n]
        if len(seg) < n * 0.8:
            continue
        spread = max(seg) - min(seg)
        wins.append((spread, s, seg))
    wins.sort(key=lambda w: w[0])
    return wins


def find_beats(seg, fs, interpolate=True):
    """Peak-pick one window. Returns beat positions, fractional when interpolated.

    The parabolic fit matters more than it looks: at 97 Hz one sample is 10 ms, so integer peaks
    quantise every interval to 10 ms steps and inflate RMSSD - which is a beat-to-beat measure -
    far more than SDNN. Fitting the peak's neighbours recovers sub-sample position and takes that
    quantisation noise out of the variability figures.
    """
    if not seg:
        return []
    thr = (max(seg) - min(seg)) * 0.22
    peaks = []
    for i in range(1, len(seg) - 1):
        if seg[i] >= seg[i - 1] and seg[i] > seg[i + 1] and seg[i] > thr:
            if peaks and i - peaks[-1] < fs * 0.4:
                if seg[i] > seg[int(peaks[-1])]:     # keep the taller of two close peaks
                    peaks[-1] = i
                continue
            if interpolate:
                a, b, c = seg[i - 1], seg[i], seg[i + 1]
                denom = a - 2 * b + c
                adj = 0.5 * (a - c) / denom if denom else 0.0
                if not -0.5 <= adj <= 0.5:      # a bad fit, not a sub-sample correction
                    adj = 0.0
                peaks.append(i + adj)
            else:
                peaks.append(i)
    return peaks


def clean_intervals(gaps):
    """Drop intervals that cannot be consecutive beats.

    Missed and doubled beats are what wreck a variability figure: one missed beat produces an
    interval of twice the truth, which alone can dominate SDNN. Anything more than 30% off the
    median is rejected rather than averaged in.
    """
    gaps = [g for g in gaps if 300 < g < 2000]
    if len(gaps) < 3:
        return gaps
    med = statistics.median(gaps)
    return [g for g in gaps if abs(g - med) <= 0.3 * med]


def heart_rate(d, fs, windows=8):
    """Heart rate and variability, only when independent windows agree.

    The agreement check is the important part, and it exists because of a specific mistake. A
    desk-bound recording gave 77 bpm and the watch's own firmware said 76, which looked like
    confirmation - but the wearer counted his pulse by hand at about 50. Both had locked onto
    the dicrotic notch, the small secondary bump in every pulse, and counted roughly three peaks
    for every two beats. Two agreeing instruments were wrong together.

    What separates that case from a good one is not signal strength but consistency: on a still
    recording the calmest windows return 50, 51, 51, 50, while on the corrupted one they scatter
    from 69 to 86. So a reading is only returned when the windows agree, and a wide scatter
    returns nothing at all. Refusing is the correct output here - a confident wrong pulse is
    worse than no pulse.
    """
    per_window = []
    for spread, _, seg in quiet_windows(d, fs)[:windows]:
        peaks = find_beats(seg, fs)
        raw = [(peaks[i + 1] - peaks[i]) / fs * 1000.0 for i in range(len(peaks) - 1)]
        gaps = clean_intervals(raw)
        if len(gaps) >= 3:
            per_window.append((statistics.median(gaps), gaps, spread, len(raw) - len(gaps)))
    if not per_window:
        return None

    rates = sorted(60000.0 / ibi for ibi, _, _, _ in per_window)
    bpm = statistics.median(rates)

    # Interquartile spread, not the full range. Beat-to-beat variability is real - the same
    # still recording legitimately gives 45, 46, 47, 48, 48, 49, 49, 51 - and judging that by
    # its extremes lets one window veto seven good ones. The quartiles ignore the tails and
    # still separate a tight cluster from a genuinely scattered one.
    if len(rates) >= 4:
        q1, _, q3 = statistics.quantiles(rates, n=4)
        scatter = q3 - q1
    else:
        scatter = rates[-1] - rates[0]
    tolerance = max(4.0, 0.08 * bpm)
    # Three windows minimum. A single window trivially "agrees with itself": the corrupted
    # recording above yielded exactly one usable window and reported 86 bpm from it, which is
    # further from the truth than the 77 the check was written to catch.
    if len(rates) < 3 or scatter > tolerance:
        return {"unreliable": True, "bpm": bpm, "scatter": scatter,
                "windows": len(rates), "rates": rates}

    # Report variability from the window whose rate sits closest to the agreed one.
    ibi, gaps, _, dropped = min(per_window, key=lambda r: abs(60000.0 / r[0] - bpm))
    allgaps = [g for _, gs, _, _ in per_window for g in gs]
    diffs = [gaps[i + 1] - gaps[i] for i in range(len(gaps) - 1)]
    return {
        "bpm": bpm,
        "ibi_ms": ibi,
        "beats": len(allgaps),
        "dropped": dropped,
        "windows": len(rates),
        "scatter": scatter,
        "gaps": allgaps,
        "sdnn_ms": statistics.pstdev(gaps) if len(gaps) > 1 else 0.0,
        "rmssd_ms": (sum(x * x for x in diffs) / len(diffs)) ** 0.5 if diffs else 0.0,
    }


def respiration_from_intervals(gaps):
    """Breaths per minute from the beat intervals themselves.

    Breathing speeds the heart on inhalation and slows it on exhalation - respiratory sinus
    arrhythmia - so the interval series oscillates at the breathing rate. On this sensor that
    signal is far stronger than the baseline wander: intervals of 1144, 1232, 1256, 1138, 1267 ms
    swing by a tenth of their length, while the baseline route barely clears the noise.

    Needs a dozen intervals to have anything to autocorrelate.
    """
    if len(gaps) < 12:
        return None
    mean_ibi = sum(gaps) / len(gaps) / 1000.0        # seconds per beat
    c = [g - sum(gaps) / len(gaps) for g in gaps]
    energy = sum(x * x for x in c)
    if energy <= 0:
        return None
    best, best_lag = 0.0, 0
    for lag in range(2, min(12, len(c) // 2)):       # 2-12 beats per breath
        s = sum(c[i] * c[i + lag] for i in range(len(c) - lag)) / (len(c) - lag)
        if s > best:
            best, best_lag = s, lag
    if not best_lag:
        return None
    period_s = best_lag * mean_ibi
    if not 2.0 <= period_s <= 12.0:                  # 5-30 breaths/min
        return None
    return {"brpm": 60.0 / period_s, "confidence": best / (energy / len(c)),
            "source": "beat intervals"}


def respiration(raw, fs):
    """Breaths per minute, from the way breathing modulates the trace.

    Breathing shows up in a PPG three ways: it moves the baseline (venous return), it modulates
    beat amplitude, and it modulates beat interval. The baseline route is used here because it
    survives at this sample rate without needing every beat detected correctly.

    A one-second average removes the pulse; a twelve-second average removes drift; what is left
    between them is the breathing band, 6-30 breaths per minute.
    """
    if len(raw) < fs * 30:
        return None
    pulse_free = movavg(raw, int(fs))
    drift = movavg(pulse_free, int(fs * 12))
    r = [pulse_free[i] - drift[i] for i in range(len(raw))]

    lo_lag = int(fs * 2.0)          # 30 breaths/min
    hi_lag = int(fs * 10.0)         # 6 breaths/min
    if hi_lag >= len(r):
        return None
    mean = sum(r) / len(r)
    c = [x - mean for x in r]
    energy = sum(x * x for x in c)
    if energy <= 0:
        return None

    best, best_lag = 0.0, 0
    step = max(1, int(fs / 12))     # a coarse lag sweep is plenty for this band
    for lag in range(lo_lag, hi_lag, step):
        s = 0.0
        n = 0
        for i in range(0, len(c) - lag, 3):
            s += c[i] * c[i + lag]
            n += 1
        if n:
            s /= n                  # divide by terms summed, not by sample count
        if s > best:
            best, best_lag = s, lag
    if not best_lag:
        return None
    conf = best / (energy / len(c))     # autocorrelation coefficient, 0..1
    return {"brpm": 60.0 * fs / best_lag, "confidence": conf}


def beat_amplitude(seg, peaks, fs):
    """Median peak-to-foot height over the beats found in this window.

    Taking the window's overall peak-to-peak instead lets a single motion step stand in for the
    pulse, which is exactly how the amplitude ends up several times too large.
    """
    amps = []
    for i in range(1, len(peaks) - 1):
        p = int(peaks[i])
        lo = max(int(peaks[i - 1]), p - int(fs * 0.35))
        if lo >= p:
            continue
        foot = min(range(lo, p), key=lambda k: seg[k])
        if seg[p] > seg[foot]:
            amps.append(seg[p] - seg[foot])
    return statistics.median(amps) if len(amps) >= 3 else None


def spo2(ch1, ch2, d1, d2, fs, cal):
    """Ratio of ratios, measured on the same beats in both channels.

    R itself is a real quantity and is reported as such. Turning it into a percentage is not:
    the familiar SpO2 = 110 - 25R is fitted for transmissive fingertip oximeters, and this is a
    reflective wrist sensor whose two channels come back with DC levels within a fraction of a
    percent of each other. Applying that formula here gave 81% where the watch said 100%. So a
    percentage is only printed once there is a calibration behind it.
    """
    wins = quiet_windows(d1, fs)
    if not wins:
        return None
    _, s, seg1 = wins[0]
    seg2 = d2[s:s + len(seg1)]
    peaks = find_beats(seg1, fs)
    if len(peaks) < 5:
        return None
    ac1 = beat_amplitude(seg1, peaks, fs)
    ac2 = beat_amplitude(seg2, peaks, fs)
    dc1 = sum(ch1[s:s + len(seg1)]) / float(len(seg1))
    dc2 = sum(ch2[s:s + len(seg1)]) / float(len(seg1))
    if not ac1 or not ac2 or dc1 <= 0 or dc2 <= 0:
        return {"r": None, "spo2": None,
                "note": "one channel is not pulsatile in the clean window"}
    r = (ac1 / dc1) / (ac2 / dc2)
    out = {"r": r, "ac1": ac1, "ac2": ac2, "spo2": None, "note": "no calibration"}
    sc = cal.get("spo2")
    if sc and len(cal.get("spo2_pairs", [])) >= 2:
        out["spo2"] = sc["a"] + sc["b"] * r
        out["note"] = "fitted to %d reference readings" % len(cal["spo2_pairs"])
    return out


# ---------------------------------------------------------------- pressure


def pulse_features(d, fs):
    """Shape features of the average beat, which is what a pressure estimate rides on.

    sut   systolic upstroke time, foot to peak, in ms - stiffer arteries rise faster
    dt    time from peak back to the next foot
    width pulse width at half height
    ai    augmentation index, the reflected wave relative to the peak
    """
    wins = quiet_windows(d, fs)
    if not wins:
        return None
    _, _, seg = wins[0]
    peaks = find_beats(seg, fs)
    if len(peaks) < 4:
        return None
    feats = []
    for i in range(1, len(peaks) - 1):
        p = int(peaks[i])
        # The foot is the start of the upstroke, so look only at the ~350 ms before the peak.
        # Searching the whole preceding interval finds the trough after the *previous* beat and
        # reports an upstroke of most of a second, which is anatomically impossible.
        lo = max(int(peaks[i - 1]), p - int(fs * 0.35))
        if lo >= p:
            continue
        foot = min(range(lo, p), key=lambda k: seg[k])
        nxt = min(range(p, int(peaks[i + 1])), key=lambda k: seg[k])
        amp = seg[p] - seg[foot]
        if amp <= 0:
            continue
        half = seg[foot] + amp * 0.5
        left = next((k for k in range(foot, p) if seg[k] >= half), p)
        right = next((k for k in range(p, nxt) if seg[k] <= half), nxt)
        mid = seg[(p + nxt) // 2]
        feats.append({
            "sut": (p - foot) / fs * 1000.0,
            "dt": (nxt - p) / fs * 1000.0,
            "width": (right - left) / fs * 1000.0,
            "ai": (mid - seg[foot]) / amp,
        })
    if not feats:
        return None
    return {k: statistics.median([f[k] for f in feats]) for k in feats[0]}


DEFAULT_BP = {
    # Placeholders, not a calibration. Shaped so a typical adult lands in a plausible range;
    # the coefficients only become meaningful once --calibrate has cuff pairs behind them.
    "sys": {"c": 105.0, "hr": 0.28, "sut": -0.055, "ai": 11.0},
    "dia": {"c": 66.0, "hr": 0.19, "sut": -0.030, "ai": 6.5},
    "pairs": [],
}


def load_calib():
    if os.path.exists(CALIB):
        try:
            with open(CALIB) as f:
                return json.load(f)
        except (ValueError, OSError):
            print("warning: %s is unreadable, using defaults" % CALIB)
    return dict(DEFAULT_BP)


def estimate_bp(feats, bpm, cal):
    def one(m):
        return m["c"] + m["hr"] * bpm + m["sut"] * feats["sut"] + m["ai"] * feats["ai"]
    return one(cal["sys"]), one(cal["dia"])


def fit_spo2(cal):
    """Fit SpO2 = a + b*R. Two reference readings at different saturations define the line;
    with only one, hold the textbook slope and move the intercept to match."""
    pairs = cal.get("spo2_pairs", [])
    if not pairs:
        return False
    if len(pairs) == 1:
        cal["spo2"] = {"a": pairs[0]["spo2"] + 25.0 * pairs[0]["r"], "b": -25.0}
        return True
    n = len(pairs)
    sx = sum(p["r"] for p in pairs)
    sy = sum(p["spo2"] for p in pairs)
    sxx = sum(p["r"] ** 2 for p in pairs)
    sxy = sum(p["r"] * p["spo2"] for p in pairs)
    den = n * sxx - sx * sx
    if abs(den) < 1e-12:
        return False
    b = (n * sxy - sx * sy) / den
    cal["spo2"] = {"a": (sy - b * sx) / n, "b": b}
    return True


def fit_calibration(cal):
    """Least squares on whatever cuff pairs exist. Needs three; more is better."""
    pairs = cal.get("pairs", [])
    if len(pairs) < 3:
        return False
    try:
        import numpy as np
    except ImportError:
        print("fitting needs numpy (pip install numpy); keeping the current coefficients")
        return False
    A = np.array([[1.0, p["bpm"], p["sut"], p["ai"]] for p in pairs])
    for key, col in (("sys", "systolic"), ("dia", "diastolic")):
        y = np.array([p[col] for p in pairs], dtype=float)
        coef, *_ = np.linalg.lstsq(A, y, rcond=None)
        cal[key] = {"c": float(coef[0]), "hr": float(coef[1]),
                    "sut": float(coef[2]), "ai": float(coef[3])}
    return True


# ---------------------------------------------------------------- main


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["hr", "spo2", "bp"])
    ap.add_argument("--secs", type=int, default=90, help="measurement length (default 90)")
    ap.add_argument("--serial", help="adb serial, if more than one device")
    ap.add_argument("--no-reboot", action="store_true",
                    help="skip the reboot; only works if nothing has measured since boot")
    ap.add_argument("--keep", metavar="NAME", help="also write NAME.csv with the raw waveform")
    ap.add_argument("--from-log", metavar="FILE", help="analyse a saved i2c log instead")
    ap.add_argument("--calibrate-spo2", type=float, metavar="PCT",
                    help="record a reference oximeter reading taken at the same time, e.g. 98")
    ap.add_argument("--calibrate", metavar="SYS/DIA",
                    help="record a cuff reading taken at the same time, e.g. 118/76")
    args = ap.parse_args()

    if args.from_log:
        with open(args.from_log) as f:
            log = f.read()
        reported = []
    else:
        serial = pick_device(args.serial)
        if not args.no_reboot:
            reboot(serial)
        log, reported = capture(serial, args.secs)

    ch1, ch2 = decode(log)
    if len(ch1) < 500:
        sys.exit("only %d samples - was it on the wrist? and has anything else measured since "
                 "the last boot?" % len(ch1))

    fs = len(ch1) / float(args.secs)
    d1 = detrend([float(x) for x in ch1], fs)
    d2 = detrend([float(x) for x in ch2], fs)

    print("\n%d samples per channel, about %.0f Hz" % (len(ch1), fs))
    if reported:
        print("the watch itself reported: %s" % reported[-1].split("value=")[-1])

    hr = heart_rate(d1, fs)
    if not hr:
        sys.exit("no clean beats in any window - too much movement")
    if hr.get("unreliable"):
        if hr["windows"] < 3:
            print("\nheart rate   no reliable reading: only %d calm window%s in the whole"
                  " recording (%s bpm)"
                  % (hr["windows"], "" if hr["windows"] == 1 else "s",
                     ", ".join("%.0f" % r for r in hr["rates"])))
        else:
            print("\nheart rate   no reliable reading: %d windows disagree by %.0f bpm (%s)"
                  % (hr["windows"], hr["scatter"],
                     ", ".join("%.0f" % r for r in hr["rates"])))
        print("             too little agreement to trust, and this is exactly the case that"
              " produces a confident wrong pulse. try again while still.")
        sys.exit(1)
    print("\nheart rate   %.0f bpm   (interval %.0f ms, %d beats, %d windows within %.0f bpm)"
          % (hr["bpm"], hr["ibi_ms"], hr["beats"], hr["windows"], hr["scatter"]))
    print("variability  SDNN %.0f ms, RMSSD %.0f ms" % (hr["sdnn_ms"], hr["rmssd_ms"]))

    resp = respiration_from_intervals(hr.get("gaps", []))
    if not resp or resp["confidence"] < 0.2:
        alt = respiration([float(x) for x in ch1], fs)
        if alt and (not resp or alt["confidence"] > resp["confidence"]):
            alt["source"] = "baseline wander"
            resp = alt
    if resp and resp["confidence"] > 0.25:
        print("respiration  %.0f breaths/min   (from %s)" % (resp["brpm"], resp["source"]))
    elif resp:
        print("respiration  %.0f breaths/min (weak - %.2f, treat as a guess)"
              % (resp["brpm"], resp["confidence"]))

    cal = load_calib()

    if args.mode in ("spo2", "bp"):
        s = spo2(ch1, ch2, d1, d2, fs, cal)
        if s is None:
            print("\nspo2         not available - no usable window")
        elif s["r"] is None:
            print("\nspo2         not available - %s" % s["note"])
        else:
            if args.calibrate_spo2 is not None:
                cal.setdefault("spo2_pairs", []).append(
                    {"r": s["r"], "spo2": args.calibrate_spo2})
                fit_spo2(cal)
                with open(CALIB, "w") as f:
                    json.dump(cal, f, indent=2)
                print("\nrecorded %.0f%% against R = %.3f (%d reference reading%s on file)"
                      % (args.calibrate_spo2, s["r"], len(cal["spo2_pairs"]),
                         "" if len(cal["spo2_pairs"]) == 1 else "s"))
                s = spo2(ch1, ch2, d1, d2, fs, cal)
            if s["spo2"] is not None:
                print("\nspo2         %.0f %%   (R = %.3f, %s)" % (s["spo2"], s["r"], s["note"]))
            else:
                print("\nspo2         R = %.3f  (AC %.1f / %.1f counts) - %s"
                      % (s["r"], s["ac1"], s["ac2"], s["note"]))
                print("             the fingertip formula does not fit a reflective wrist "
                      "sensor; use --calibrate-spo2 against an oximeter")

    if args.mode == "bp":
        feats = pulse_features(d1, fs)
        if not feats:
            print("\npressure     no clean pulse shape to work from")
        else:
            cal = load_calib()
            if args.calibrate:
                try:
                    sysv, diav = (float(v) for v in args.calibrate.split("/"))
                except ValueError:
                    sys.exit("--calibrate wants SYS/DIA, e.g. 118/76")
                cal.setdefault("pairs", []).append({
                    "bpm": hr["bpm"], "sut": feats["sut"], "ai": feats["ai"],
                    "systolic": sysv, "diastolic": diav,
                })
                fitted = fit_calibration(cal)
                with open(CALIB, "w") as f:
                    json.dump(cal, f, indent=2)
                print("\nrecorded %.0f/%.0f against this reading (%d pair%s on file)%s"
                      % (sysv, diav, len(cal["pairs"]), "" if len(cal["pairs"]) == 1 else "s",
                         "; coefficients refitted" if fitted else "; 3 pairs needed to fit"))
            calibrated = len(cal.get("pairs", [])) >= 3
            if calibrated:
                sbp, dbp = estimate_bp(feats, hr["bpm"], cal)
                print("\npressure     %.0f/%.0f mmHg   (fitted to %d cuff readings)"
                      % (sbp, dbp, len(cal["pairs"])))
            else:
                # No number until there is a calibration behind it. Printing a population-fitted
                # guess here would be the same failure as the vendor firmware's "blood pressure",
                # which emits canned values off a heart-rate threshold - see docs/vitals.md. A
                # plausible-looking number is worse than none, because it gets believed.
                print("\npressure     not calibrated, so no number. take a cuff reading at the"
                      " same time as")
                print("             a capture and pass --calibrate 118/76; three pairs fits it"
                      " to you.")
            print("shape        upstroke %.0f ms, width %.0f ms, augmentation %.2f"
                  % (feats["sut"], feats["width"], feats["ai"]))

    if args.keep:
        path = args.keep if args.keep.endswith(".csv") else args.keep + ".csv"
        with open(path, "w") as f:
            f.write("ch1,ch2\n")
            for a, b in zip(ch1, ch2):
                f.write("%d,%d\n" % (a, b))
        print("\nwaveform written to %s" % path)


if __name__ == "__main__":
    main()
