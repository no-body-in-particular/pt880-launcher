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

## Blood pressure is not a measurement

Worth stating plainly, because the number looks like the others and is not like them at all.

`gh3011_service` computes the pressure in `FUN_0002cde8`, and that function contains no pressure
model: no pulse transit time, no waveform morphology fitted to anything, no calibration. It is a
cascade of hand-written threshold rules that ratchets a value upward from a fixed set:

```c
uStack_34 = 0x28;                                  // start at 40
if (...) uStack_34 = (short)iVar14 - 10;           // a feature, minus a constant
if (...) { iVar31 = 0x41; ... max(iVar31, uStack_34) }   //  65
if (... && uStack_34 < 0x55) uStack_34 = 0x55;     //  85
if (... && uStack_34 < 0x66) uStack_34 = 0x66;     // 102
if (... && uStack_34 < 0x73) uStack_34 = 0x73;     // 115
if (... && uStack_34 < 0x78) uStack_34 = 0x78;     // 120
if (... && uStack_34 < 0x3c) uStack_34 = 0x3c;     //  60
```

Start at 40; each rule that fires raises the number to a floor - 65, 85, 102, 115, 120 - or to
some counter minus 5 or 10. The result is kept only if it lands in 41 to 109
(`uVar6 - 0x29 < 0x45`).

That is why every reading this watch has ever produced sits in 116-123 over 77-81 regardless of
who is wearing it, and why `bp1:117` held for 158 consecutive log lines without moving.

It also explains the searching that went nowhere. Published PPG blood pressure methods use pulse
transit time between two points, or a regression trained on waveform features; this matches
none of them because it is not from that literature. There is no paper to find and no reference
implementation to compare against.

The physics is the reason nothing better is available from this hardware. Pressure relates to
how fast the pulse wave travels, and a velocity needs two points in time - ECG to PPG, or two
PPG sites. One sensor on one wrist has no transit to measure, so anything single-site is
inferring pressure from the shape of the wave, which is a correlate and not a measurement.
Single-PPG estimators need per-person calibration against a cuff, drift within hours, and the
published ones sit around 8-10 mmHg of standard deviation - wider than the clinical threshold.

**So the pressures on the chart are decoration.** Heart rate, SpO2 and temperature are real
sensor readings. The pressures are a decision tree emitting plausible numbers. Keeping them is
defensible - the stock firmware shows the same figures - but they should not be read as health
data, and a reimplementation of that cascade would reproduce the decoration rather than improve
on it.

### What the raw waveform could honestly support

The chip does produce a real waveform, one sample at a time:

    ppg=49090,accx=496,accy=-32,accz=-8112

From that, these are genuine and worth having: **HRV** from beat-to-beat intervals, **respiration
rate** from the amplitude and baseline modulation, and pulse waveform indices such as
augmentation and stiffness index - which correlate with arterial stiffness and are honest about
being their own quantity rather than a pressure.

Getting at it is the open problem. The daemon logs the samples but an ordinary app cannot read
another process's log, and `/dev/socket/gh30x_socket` - which init creates and its own rc even
tries to `chmod 666` - is never listened on: `/proc/net/unix` shows it without SO_ACCEPTCON, so
connecting to it returns ECONNREFUSED. The remaining routes are an ioctl on `/dev/gh_tools` that
returns the FIFO, or installing this launcher as a system app so it can read the daemon's log.

## The zero-light pedestal, and what it did to R

Both FIFO channels carry a fixed offset of 0x300000 - 3,145,728 - that is not light. Driven dark
by a low gain, channel 1 read 3,145,747 at two different gains while channel 2 tracked the gain
properly, which is what a converter's zero-light code looks like.

It matters because R is `(AC/DC)` per channel, and for most of this project the DC in that
expression was 98% pedestal:

    raw codes      dc1 = 3,149,644   dc2 = 3,191,720     within 1.3% of each other
    actual light   l1  =     3,916   l2  =    45,992     a factor of twelve

So R was comparing two numbers that were almost entirely the same constant, and it came out
around 0.07. Against received light the same measurements give 2.1 and 2.4, which is at least a
number of the right kind.

Two things follow. The first is that any earlier conclusion drawn from raw DC needs re-checking,
because a change of a few thousand counts is invisible against a 3.14M baseline - that is why
`0x011c`, `0x011e` and `0x0120` were originally ruled out as per-channel LED current. Re-tested
against light they are still ruled out: `0x011c` moves both channels down together (3597 -> 3428
and 41717 -> 39683), `0x011e` moves neither, and `0x0120` stops the stream. The ruling-out stands;
it just now rests on evidence that could have shown the opposite.

The second is that SpO2 is still not measurable here, and for a reason no amount of arithmetic
fixes. Channel 1 receives twelve times less light than channel 2 and carries **2 counts** of
pulsatile amplitude against channel 2's 8 to 12. R across three consecutive resting measurements
came out 2.10, 2.38 and 4.75. That spread is not saturation changing, it is two counts of signal
being divided by itself.

**So no percentage is reported.** The `a1 > 5` gate suppresses it, which is why measurements print
`spo2=0` and the launcher publishes nothing for it. A watch that displays a saturation it cannot
measure is the thing this project exists to stop doing - and the earlier version of this code did
exactly that, printing a clamped 100% on every run.

Making it measurable needs channel 1's light raised from 3,916 toward channel 2's 45,992. The
per-slot LED current is the obvious lever and has not been found: the captured sequence's writes
to `0x0132` and `0x0134` do not stick, though the same writes applied explicitly after the
sequence do, which is a thread worth pulling.

## 0x0180 balances the two channels, and costs the pressure to do it

Setting `0x0180` to zero, where the captured sequence leaves it at `0x004d`, moves the light each
channel receives from 4,200 against 53,000 to 18,500 against 32,800 - from a factor of twelve to
a factor of 1.8. Channel 1 goes from about two counts of pulsatile amplitude to thirty or forty.

That is the thing that made R meaningless, so it is worth writing down properly. It was found by
setting each configuration register to zero in turn and watching the light each channel received,
a search that only became possible once the DC pedestal was subtracted: against a raw code of
3.14 million, quadrupling channel 1 looks like a rounding error. Of thirty-two registers only two
moved it - `0x0180`, and `0x0110`, which overshoots and puts channel 2 below channel 1.

**It is not the default.** Two reasons, and the first is decisive:

- Channel 2 carries the pulse shape the pressure is derived from, and its amplitude falls from
  190-260 counts to 34-95. Six measurements in that state found no usable beats at all and
  returned no pressure. A working pressure is not worth trading for a saturation that is still
  not measuring.
- R is steadier than it was and still not steady. Two runs gave 0.730 and 0.741, which looked
  like an answer; five later ones gave 0.957, 1.014, 1.113, 1.192 and 1.324. That is sixteen
  percent within a session and a different centre between sessions, on a wearer who was resting
  for both.

So it stays an override - `ppgd 45 "" spo2 0180=0000` - and the work carries on from there. What
it would take next is a way to raise channel 1 without lowering channel 2, which is a different
register from either of these two, or none.

## Can red and green run together? No, and here is what was tried

The FIFO carries exactly two channels. `ppgd` had only ever assumed that - it reads the level,
multiplies by three because a sample is 24 bits, and walks the bytes six at a time - so it was
worth checking rather than believing. Printing consecutive 24-bit samples with no frame assumption
and asking how well the run repeats settles it:

    every 1 sample : mean step 12736
    every 2 samples: mean step 15        <- the frame
    every 3 samples: mean step 12733

Two channels, alternating. Not three, and forcing a third by writing `0x012c=0x0007` - it holds
`0x0003` in red mode and `0x0006` in green, which reads like a slot-enable mask - stops the FIFO
producing anything at all.

The three slot registers looked like the way in. `0x0130`, `0x0132` and `0x0134` hold
`0x0346/0x0446/0x0546` in red mode against `0x0746/0x0346/0x0246` in green: a high byte that
tracks the mode over a low byte that never changes, which is the shape of "this slot drives LED n
at current 0x46". If it were, a slot set to green beside a slot set to red would put both
wavelengths in the same two-channel FIFO - interleaved rather than simultaneous, but from the same
beat, the same contact and the same moment, which is worth more than either alone.

It is not. Moving the high byte around changes neither channel's light:

    slots                       channel A       channel B
    0346 / 0446 / 0546 (stock)    3,910          42,084
    0746 / 0446 / 0546            3,955          42,572
    0746 / 0446 / 0546 both set   4,041          44,602
    0346 / 0746 / 0546            4,157          45,509

The swings differ between those runs, but that is the pulse and the wrist, not the assignment.
The light does not move, and the light is what a wavelength change would move.

Two things are worth recording from the attempt. The slot registers read back `0x0000` after the
captured sequence and hold their values after an explicit write followed by a commit
(`0xdddd=0xc1`), so the sequence's writes land in a shadow that only a commit makes readable -
which is why an earlier note here said only one slot was configured. And the mode ioctl on its own
proves nothing while the red configuration table is being replayed over it: every mode from 2 to 7
gives the same 1:10.5 light ratio, because the table sets the LEDs and the mode does not override
it. That matches the LED staying visibly red through mode 4 earlier in this file.

So the only lever on the balance remains `0x0180`, and it trades one channel against the other
rather than raising either.

## The gates work within a session and not between them

Ten logged passes split into six that agreed and four that did not, and two measurements separate
them exactly: the window spread of one pass, and whether the matched filter and the bin estimate
agree about the same amplitude. Kept passes ran 0.583 to 0.729 with a median of 0.667; the four
rejected were 0.190, 1.531, 2.044 and 2.739.

Validated on a fresh session it does not hold. The same gates, the same wrist, nobody moved:

    session 1, gated:  0.583  0.625  0.664  0.669  0.682  0.729     median 0.667
    session 2, gated:  0.825  1.060

Between sessions R moved by a quarter to a half. At roughly 25 saturation points per unit of R,
that is a swing of several percent in what it claims to measure - and R is precisely the quantity
that is not supposed to do this. Dividing AC by DC on each channel exists to cancel how much light
happens to be getting in, which is what changes when a watch sits differently on an arm. It is not
cancelling.

That points at the DC term rather than the AC one. The likeliest cause is light reaching the
detector that no LED put there: ambient leaking under the case adds to DC without adding to AC, it
varies with how the band sits, and it corrupts the normalisation exactly this way. Most PPG front
ends sample a slot with the LED off to measure and subtract it, and there is a third slot here
whose purpose has never been established.

Testing that needs a reading with the LEDs dark, and zeroing the current byte of the slot
registers stops the FIFO rather than darkening it - the same touchiness these registers showed
when a third channel was forced. So it stands as the most likely explanation and not as a
established one.

**Nothing is published.** Six passes agreeing to eleven percent is the closest this has come, and
it did not survive the wrist being worn again.
