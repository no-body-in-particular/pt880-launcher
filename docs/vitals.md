# Where the vitals actually come from

Three numbers, three different paths, and none of them the obvious one. Written down because
every wrong turn below cost hours, and the shape of the thing is not guessable from the code.

## The short version

| | source | why not the obvious place |
|---|---|---|
| heart rate | `/dev/input/event1`, or the service | the HAL delivers nothing when it wedges |
| SpO2 | the service, or `/dev/input/event1` | the driver's copy often has not converged |
| blood pressure | `libICJniUtils` only | not on the input device at all |

The vendor service (`com.ic.work`) starts every measurement, whichever path reads the result.
Nothing in userspace can start one: `goodix_health` exposes no sysfs control, there is no
character device for it, and the i2c protocol is the vendor's own. Registering a listener does
switch the sensor on - `switched on gh30x_sensor (type 21)` - but that alone runs no
measurement; 45 s of it produced not one sample.

## The HAL is the part that breaks

During a measurement the framework shows the sensor activated and connected, and then sits
there:

    gh30x_sensor  handle=0x00000008, active-count=1, connections=1
    gh30x_sensor | status: First flush pending | pending flush events 0
    last=< 60.0,120.0, 79.0>        unchanged across 29 s and every sample taken

"First flush pending" means the connection opened and not one sample ever arrived. The
`last=` triple freezes at whatever it last held, which is why readings were identical for
hours, and why `com.ic.work` reports `high/low/heart/oxygen` all zero - it is handed nothing,
so it has nothing to report. The zeros are the HAL's silence written down.

**A reboot clears it.** It wedges rather than breaks. Force-stopping `com.ic.work` also often
clears it, which is worth knowing before assuming the hardware has failed.

## The driver underneath is fine

Reading `/dev/input/event1` across the same measurement gives a value every second. The node is
`crwxrwxrwx`, so no root and no permission is needed.

    REL_RX  0x395c -> 57 bpm,  92 %     settling
    REL_RX  0x3a50 -> 58 bpm,  80 %
    REL_RX  0x3b64 -> 59 bpm, 100 %     converged

A reading is two bytes in one `REL_RX`: heart rate high, SpO2 low. `REL_RZ` is a once-a-second
progress tick (`0x0101`, `0x0201` ... `0x1d01`) and carries no measurement. `REL_RY` is declared
in `capabilities/rel` (0x38 = RX, RY, RZ) and **has never once been emitted**, including during
the very measurement that produced 123/81.

## Blood pressure is derived above the driver

Since it is not on any axis, it comes from `libICJniUtils.so`, which exports the vendor's own
algorithm and reaches the chip through `/dev/gh_tools` rather than through the HAL:

    Java_com_ic_jni_ICJniUtils_getHighBloodPressure
    Java_com_ic_jni_ICJniUtils_getLowBloodPressure
    "is wared %d , ppg %d , spo2 %d , bph %d , bpl %d"

It links only libc, liblog, libm and libstdc++. Signatures were read out of the `method_ids` of
`ICTemperatureTest.odex`, which declares the same class - all `static native`, all `int` except
the temperature pair. The declarations must live in a class named exactly `com.ic.jni.ICJniUtils`
because the library exports by name with no `JNI_OnLoad` and no `RegisterNatives`.

### Computing it ourselves is not on

Asked and answered: it needs the raw PPG waveform, which is not on the input device - `REL_RX`
carries the already-derived bpm and percentage, not samples. Reaching the waveform means `ioctl`
on `/dev/gh_tools`, so native code either way. And single-PPG pressure estimation is a calibrated
per-subject model rather than a formula; published approaches want a second signal (PTT from
ECG+PPG, or two sensors) and do not transfer between sensors. A port would produce numbers that
look like blood pressure and track nothing, which is worse than a gap.

## The pressures only exist at the end

**This is the part that bites.** The pair arrives in the callback that *ends* a measurement.
Before that, the service reports what the algorithm has reached so far, and
`getHighBloodPressure()` polled mid-run returns a number on its way somewhere.

Timing out early does not mean waiting a little less. It means taking the intermediate
`onHeartRateUpdate` callbacks instead of the final `onHeartRateGet`, and their pressures are
unsettled. It showed up in two stages, both on a sleeping wrist:

    before   118/78  119/79  120/79  121/80  122/81  123/81      tight
    after    103/68  106/70  110/73  117/77  124/82  127/84      ragged
    then     nothing at all, for six hours

So `VITALS_TIMEOUT_MS` has to exceed the longest measurement the odex allows, plus the several
seconds the sensor spends finding a pulse before the first reading counts. And a measurement
that did not finish contributes no pressure at all - the pulse and the percentage from a partial
measurement are fine, only the pair is dropped.

## How long a measurement runs

Two independent stops, and the shorter one wins:

- `checkCount > 10` - readings delivered. `tools/patch_ic_work.py --window N` raises it.
- `noWearCount > 20` - roughly 20 s of the wear detector reading false.

Off the wrist the second fires first, so measurements end at ~21 s with `checkCount` still in
single figures and the window setting never comes into play. That is correct behaviour and not
a fault; it is also why the window patch cannot be tested on a desk.

Raising the window helps the driver's SpO2, which often ends still climbing, but it lengthens
every measurement - more sensor-on time, more battery, and the timeout above has to keep up.
Twenty readings lands around 35-40 s. Thirty runs 40-55 s and left no headroom.

## SpO2 converges in two stages

It does not arrive, it climbs, and the first plateau looks exactly like an answer:

    22, 24, 80, 81, 81, 81, 82, 82, 96, 97, 97

Five or six samples at 81-82, then a jump to the high nineties. A window ending inside the false
plateau yields 81, which is a reading nobody took. Stability alone cannot tell the two apart -
the false plateau is perfectly stable - so the test is the final pair agreeing (or the last three
within a few points, since a moving wrist jitters where a resting one does not) **and** a floor
of 90.

The floor has a real cost, stated so nobody removes it by accident: a genuine desaturation into
the eighties would be refused. On a sensor whose every measurement climbs through that band, an
82 cannot be distinguished from the artefact, and the vendor's own algorithm never reported
below 95 for the same wrist at the same time.

Driving is worse than resting, and by more than the noise suggests: at rest the tail reads
`82, 82, 100, 100`, in a car `82, 93, 90, 91`.

## Cross-checking, and why

Both sources watch the same window through the same chip, so when both are live their pulses
agree exactly - 60 and 60, 61 and 61, 58 and 58. That agreement is what says the service is
*live* rather than repeating a stale triple, which is the failure that started all of this and
the one thing a plausible-looking value cannot rule out on its own.

So the service leads and the driver checks it. When they disagree the driver's pulse is kept,
because that one has a raw stream behind it, and the rest of the service's reading is dropped
rather than guessed at. When the service has nothing, the driver fills in.

**A refusal must stay refused.** Discarding a window and then asking the service for a value
puts back exactly what the refusal removed. On a wrist that sat at 51-55 bpm all night, every
spike that reached the server - 77, 73, 70, 66 - came from a window the driver had already
thrown out.

## Temperature

The thermometer reads the wrist, and a wrist is not a body: it sits a few degrees above the room
and well below its owner, which is how 21 C got filed as a body temperature. The vendor does not
use it raw either - `com.ic.work` converts in its own `onSensorChanged`:

    iget-wide  offset 24              the wrist reading
    invoke-static a(wrist, ambient)   -> getBodyTempFromWristTemp
    iput-wide  offset 32              the body temperature

with a constant 26.0 standing in for ambient whenever the measured one is unusable. There is no
ambient thermometer here, so 26.0 is what gets passed - the vendor's own fallback. The conversion
leans on the difference between skin and surroundings, so in a cold room or outdoors the result
drifts. It is a wrist thermometer, not a clinical one.

The plausibility band is 34-43. It was 20-45, which is what let the raw wrist reading through.
