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

## The gates work within a session and not between them (partly retracted below)

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

## Two retractions, and what the outliers actually were

**The ambient hypothesis is wrong.** The section above proposed that light leaking under the case
was corrupting the DC normalisation. It can be solved for without ever darkening the LED: both
channels are time slots on the same photodiode, so ambient reaches them equally, and across
measurements `m2 = k*m1 + A(1-k)` - a line whose slope is the true LED ratio and whose intercept
gives the offset. Regressing fourteen measurements gives `m2 = 1.7335*m1 - 919` at R2 = 0.796, so
A is about 1,250 counts, seven percent of channel 1.

Correcting for it moves every ratio by a uniform three percent and closes none of the gap:
0.583-0.729 becomes 0.602-0.753, and 0.825/1.060 becomes 0.848/1.096. Ambient is real, small, and
not the problem.

Sweeping the LED current byte from 0x46 down to 0x01 - seventy-fold - also moves the light not at
all: channel 1 stays between 3,923 and 4,170 throughout. Taken with the high byte not remapping
any LED, those three slot registers do nothing this sensor responds to, and the low byte is not a
current.

**And they were not two sessions.** All fourteen measurements span 10:17 to 10:54 of one morning,
with the watch never off the wrist - the thermometer holds 33.7 to 34.8 across the whole of it.
The four outliers fall between 10:36 and 10:41, which is exactly the window in which the slots and
LED-current experiments were running: stopping the daemon, rewriting registers and power-cycling
the chip underneath the measurements being logged.

So the outliers are self-inflicted, and the spread they produced was used to justify a gate and
then to declare that gate a failure. Both conclusions were drawn from contaminated data. The
uninterrupted stretch, 10:17 to 10:24, reads 0.729, 0.625, 0.583, 0.664 and 0.682 - within eleven
percent, with nothing rejected.

## Why the vendor manages it in eight seconds, and we do not

The obvious hope was that it never computes a saturation at all - that the chip or the driver
produces one and `gh3011_service` only reads it. Eight seconds would then be the chip converging
rather than a daemon working, and everything in ppgd would be reimplementing something already
available. Two places to look, and both are now closed.

**The derived-report ioctl is a mode readback.** `_IOR('G', 11, 24)` - twenty-four bytes, the
right size for a handful of results - returns `06` in the first byte before the chip is started
and `05` after starting it in mode 5, with the remaining twenty-three bytes zero. It reports which
mode the driver is in. There is nothing derived about it.

**The driver emits no reading of its own.** This file records that a reading arrives on
`/dev/input/event1` as a single `REL_RX` with the heart rate in the high byte and SpO2 in the low
one. Listening on that device for a hundred seconds while a full measurement ran gives nothing at
all - no `REL_RX`, no events of any kind.

The reader is not at fault, which took three attempts to establish. The accelerometer was silent
because it is idle between sleep bursts, and stays silent with `enable` set to 1 - its sysfs value
reads `0 0 0`. The thermometer is live, and listening there produces exactly what it should:

    type=3 code=0 value=3387        EV_ABS, 33.87 C

So the tool hears input events when there are input events to hear, and `event1` has none while we
drive the sensor.

That places the vendor's algorithm in userspace, in the daemon, where the earlier search for a
callable entry point already put it: the HAL exports nothing, `libICJniUtils` exports nothing, and
the daemon is stripped to three `getopt` symbols. The eight second cycle is Goodix's algorithm
being better than ours, not the hardware handing over an answer. There is no shortcut to take.

## What the vendor actually does for SpO2, and what that costs

The strings in `gh3011_service` answer it. It is Goodix's own example code calling Goodix's own
library:

    example code v0.1.6 (For hbd_ctrl lib v0.5.6.0 and later)
    hbd ctrl version: %s     spo2 version: %s     hba version: %s
    spo2 calc, gs_len=%d, result=%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d
    GH_3011 spo2_result : %d , lvl %d , hb %d, lvl%d , wearing %d
    gh_dev_report_key %d, weared %d ,ppg %d ,spo2 %d ,ret %d
    packages/apps/IC/gh30x-service/gh3011/libs/main/gh_sensor_romaii_hook.c

So the vendor is not computing a saturation either. It hands twelve results back from `hbd_ctrl`,
a proprietary library statically linked into that binary, and reports the one it wants along with
a confidence level. `gh_dev_report_key` is the call that writes the `REL_RX` events this file
records - which is why listening on `/dev/input/event1` while our own code drives the sensor
hears nothing at all.

The obvious idea is to run their daemon for a saturation and ours for everything else. It does not
work as stated. Started on its own it produces no output and no log lines of any kind, because it
is not a program that measures - `GH30xService::init`, `GH30xService::start MeasureType` and
`GH30x_CMD_Handler 0x%04x %d param %d` describe a service waiting to be told. The vendor app
drives it, and a measurement only happens when something asks.

That leaves the honest options as they were. The vendor path can be restored whole - the launcher
still has `VendorVitals` and the binary is kept as `gh3011_service.real` - and it would bring back
a saturation with a real algorithm behind it. What it costs is the chip: two drivers cannot hold
one sensor, so that is a saturation instead of our heart rate and pressure rather than alongside
them. Alternating between the two per cycle is possible and has not been tried.

The remaining route to a saturation of our own runs through `hbd_ctrl` itself, which is in that
binary and is exactly the kind of thing Ghidra found `FUN_0002cde8` in.

## Running the vendor daemon: what it costs and what it gives

It can be run. Two things had to be right, and the notes above already said both:

- init starts it as `gh3011_service --daemon` with a socket it creates itself, so the wrapper has
  to pass the arguments through - `exec ... "$@"`, not a bare exec. Without them it crash-loops,
  which is what `init.svc.gh3011_daemon` reading `restarting` means.
- and `printf` does not exist on this device, so writing the wrapper with it truncates
  `/system/bin/gh3011_service` to zero bytes and the service cannot start at all. Same shape as
  the `sed` incident that once emptied wpa_supplicant.conf: check the tool exists before
  redirecting its output over something that matters.

With those right the daemon runs, creates `/dev/socket/gh30x_socket`, and can be watched through
the LD_PRELOAD tap - 119 kB of its conversation with the chip in ten minutes, including reads of
registers our own sequence never touches.

**What it does not do is produce a reading.** Ten minutes on a still, sleeping wrist:

    GH_3011 hb_result : 0 , lvl 0 , wearing 1
    gh_dev_report_key 0, weared 1 ,ppg 0 ,spo2 0 ,ret 0

repeated every couple of seconds, unbroken. It knows the watch is worn. Its heart rate is 0 at
confidence 0 and its saturation is 0, and it stays that way, because nothing is commanding it:
`GH30xService::start MeasureType` and `GH30x_CMD_Handler` are what a client sends over that
socket, and `com.ic.work` is the client. The daemon on its own sits in wear-detection and scans.

And scanning means the LED is lit continuously. The wearer noticed it before the log did - the
sensor stuck on with nothing stopping it. Stopping the service leaves the chip powered, because
`ctl.stop` kills it without the cleanup path that writes `0xdddd=0xc4`; any of our own tools
powers it down on exit, which is how it was recovered.

So "use the vendor daemon" is not a middle path. Running it idle costs the LED and returns
nothing; getting a reading out of it needs the whole vendor stack driving it, which owns the chip
and leaves no room for ours. The one arrangement that could give both is on demand: hand the chip
over for a single vendor measurement and take it back, which needs root and is therefore vitalsd's
job rather than the launcher's. That has not been built.

## What the vendor does that we do not: it reads the accelerometer

The daemon can be watched properly now. Run it under the LD_PRELOAD tap with its arguments passed
through, leave our own socket absent so the launcher falls through to `VendorVitals`, and it
performs real measurements while every ioctl is logged. On a sleeping wrist it produced:

    GH_3011 hb_result : 51 , lvl 93 , wearing 1
    GH_3011 hb_result : 53 , lvl 96 , wearing 1
    GH_3011 hb_result : 54 , lvl 100 , wearing 1

A correct rate at confidence 100, and `spo2 0` throughout - this is the green heart-rate mode,
which is what the wearer said all along the vendor leans on.

Its start-up is not the difference. `0x0102=0x09c4`, `0x0104=0xba98`, `0x0186=0x1807` are what
`seq_hr.h` already replays, so the configuration captured earlier was right.

The difference is the loop. Over 1,728 ioctls during working measurements:

    00004701  wait for interrupt          652
    40184709  set mode                    422
    825a470a  read accelerometer FIFO     420      <- we never call this
    c0044708  get mode                    231      <- nor this
    40044702  power                         3

It reads the accelerometer on roughly two of every three sensor interrupts. That is not a wear
check or an occasional sample; it is inside the measurement loop, which is what a PPG algorithm
does when it means to subtract the arm's movement from the optical trace. Every commercial tracker
does this and it is the standard answer to the complaint that started this - that motion should
not destroy a rate. Ours reads the optical channel alone and has nothing to subtract with.

The driver already exposes it: `_IOR('G', 10, 602)` returns an accelerometer FIFO, and 602 bytes
is a hundred three-axis samples. Nothing else about it needs discovering.

What this capture does not contain is a saturation. The vendor never entered SpO2 mode during it -
`spo2 0` on every line - so which `MeasureType` selects it, and what `hbd_ctrl` does with the two
channels once there, is still unseen. The capture is kept as docs/data/vendor-tap-2026-08-30.txt.

## Asking the vendor daemon for a saturation, and being refused

The obvious way to request one is the mode ioctl. `setmode.c` says the daemon reads the mode with
`_IOWR('G',8,4)` and configures the chip to match, and mode 5 is red and infrared - so setting it
while the vendor daemon runs should ask for a saturation. It does not:

    set mode 5 -> rc=0
    driver reports mode 4

The write succeeds and the mode is 4 a moment later. That is the daemon overriding it, and the
capture already showed it doing so four hundred and twenty-two times in one session - `set mode`
is something it issues, not something it obeys. A client cannot choose the measurement this way.

Which leaves the binder interface. `com.ic.work.SensorDataService` takes a `MeasureType`, and that
is what selects a saturation; `VendorVitals` in the launcher already speaks it, which is how the
original system got one. So a capture of the vendor computing SpO2 needs the vendor app asking for
it, not us poking the driver underneath.

Worth remembering alongside this: the saturation is the slow half of a vendor measurement. This
file records elsewhere that it climbs rather than arrives, that the driver's copy is often still
converging when a reading is taken, and that raising the window helps it. A capture long enough
to see a rate is not necessarily long enough to see a saturation, and every capture so far has
shown `spo2 0` beside a perfectly good `hb_result`.

## How the vendor computes a saturation

Its own strings name the call, and Ghidra reads the rest. `FUN_0001b7c0` is what the daemon calls
just before logging `spo2 calc, gs_len=%d, result=...`, and inside it:

    FUN_00016c04(samples, len, shift)          preprocess the buffer
    handle = FUN_000186ac()                    window state, carried between calls
    scale  = FUN_00016668(handle, len)
    do {
        FUN_000168f0(handle, i, samples, len, scale, shift, feat, ...)   per-window features
        valid = FUN_00023500(feat, out)                                  the computation
        FUN_00019c28(feat, status)
        if (valid & 1) { *result = out[0]; *level = out.level; }
    } while (more windows)

That is a real signal-processing pipeline: a preprocessing pass, a windowing state object carried
across calls, per-window feature extraction into a forty-byte structure, and a routine that turns
those features into a percentage and a confidence, looping until a validity bit comes back set.

It is worth stating plainly because the same treatment of the blood pressure found the opposite.
`FUN_0002cde8` contains no pressure model at all - a cascade of hand-written thresholds ratcheting
a value upward from a fixed set, which is why this project stopped publishing the vendor's
pressure. The saturation is not that. Whatever else is true of it, somebody implemented an
algorithm.

What follows from that is discouraging for copying it. There is no ratio-of-ratios and a curve to
lift; the arithmetic that matters is four functions down, operating on structures whose fields are
unnamed, with state threaded between windows. Reimplementing it from decompiled ARM is a project
in itself, and the result would be a reimplementation of a proprietary algorithm rather than an
understanding of one.

The useful conclusion is narrower and holds regardless: the vendor gets a saturation because
Goodix wrote a good algorithm, not because the hardware hands one over. Our own channel 1 carries
two counts of pulse where channel 2 carries sixty, and no algorithm recovers a ratio from that -
which is why the work that matters is `0x0180` and the balance between the channels, not the
arithmetic applied afterwards.

## Calling the vendor's algorithm from outside its daemon

The mechanism works and the state does not, and the second is the harder half.

`gh3011_service` is a PIE - an ELF of type DYN, which is a shared object that happens to have an
entry point - so `dlopen` maps it. It came up at 0xb6e68000 with the saturation routine at a known
offset from there, the base read out of /proc/self/maps because nothing in a PIE's dynamic table
points at a static function. So the code is addressable and callable with no daemon, no command
socket, and no argument over the chip.

Calling `FUN_0001b7c0` cold segfaults. Its opening lines dereference a pointer loaded from the
data section, and that pointer is set during start-up; `dlopen` runs a binary's constructors but
not its `main`, so it is null.

Running their own initialisation first does not fix it, and the reason is plainer than the
symptom. `GH30xService::init` is a C++ member function. It wants `this`, and calling it through a
`void (*)(void)` hands it whatever happened to be in the argument register - so it segfaults
before doing anything, exit 139. Getting a real `this` means constructing the service object,
which means running the part of `main` that builds it, which is most of the daemon.

So the position is: their algorithm can be reached but not used, because it is a method on an
object rather than a function on data. What would make it work is not a cleverer call but the
constructor, and at that point the honest description is running their daemon with extra steps.

Recorded because the mechanism is worth knowing and because the failure is specific: this is a
C++ object lifetime problem, not a permissions or relocation one, and nothing about it gets easier
with a better guess at the arguments.

## The level gate would throw away our best readings

Their check refuses a measurement whose level is outside the window its mode expects, and for
heart rate that window starts at 28626. Ours measure well below it, so the obvious reading is
that our gain is too low and raising it would fix the low bias this file has recorded for weeks.

Forty-two recordings from 30 August say otherwise. Level against reported rate, with the cuff
reading 49 to 50 throughout:

    level below 10,000     bpm 49, fifteen times out of fifteen, no scatter at all
    level 18,000 - 32,000  bpm 37 to 52, mean about 45
    level 43,000 - 55,000  bpm 44 to 48, mean about 45

The low group is both the most accurate and the only precise one. It is also the one their gate
rejects hardest.

This is not the recordings being taken at different times of day. The groups interleave minute by
minute - 16:04, 16:05 and 16:06 at level 7,800 reading 49, then 16:07 at 23,396 reading 44, then
16:09 back at 7,997 reading 49 - same wearer, same sitting, minutes apart.

The 47,000 between the 8,000 group and the 55,000 group is the 47,600 the channels are already
known to sit apart, so those two are the same gain with the other channel picked. The choice of
channel decides the answer, and the dimmer channel is the one that gets it right.

So the gate stays reported rather than enforced, and raising the gain to clear their floor would
have made the measurement worse while making it look more legitimate. What the number is good for
is spotting which channel was picked, and that is worth more here than a refusal.

## Testing 0x0084 on the wrist: the knob is real, the 5 bpm gap is not

Their ladder moves one register, so it can be set directly. Three forty-second measurements at
each of the two interesting values, same wrist, alternating, daemon stopped:

    0084=0021   level 4552  hr 53 spread 4
    0084=0022   level 48367 hr 53 spread 2
    0084=0021   level 4484  hr 54 spread 4
    0084=0022   level 48318 hr 55 spread 2
    0084=0021   level 4471  hr 54 spread 3
    0084=0022   level 48340 hr 56 spread 3

The register controls the level regime outright and reproducibly: 4,500 against 48,300, steady to
about eighty counts across runs. That is the same 47,000 the two channels have always been
recorded as sitting apart, so this register is what decides which of them the shared gain favours.
Writing it also halves the sample rate, from 99.7 Hz to 49.8, so it is a slot or timing setting
rather than a plain current.

What did not survive the test is the interesting part. The archived recordings split cleanly - a
level near 8,000 read 49 fifteen times out of fifteen while levels above 18,000 read 44 to 46 -
and the obvious conclusion was that the dim setting is right and the bright one reads five bpm
low. Measured deliberately, the two agree to about one bpm.

So the archive split was not caused by the level. Measurement length and which channel got picked
varied along with it, and one of those is the likelier cause. Neither setting is better on this
evidence: the dim one repeats more closely across runs, 53/54/54 against 53/55/56, and the bright
one has the lower spread inside each measurement, 2/2/3 against 4/4/3.

Nothing here says which is more accurate, because there was no cuff reading taken alongside it.
That is the measurement still missing, and it is a small one: a cuff pulse taken during a pair of
these runs would settle it.

## Why the bright setting drifts: we regulate the wrong quantity

Capturing at both 0x0084 settings an hour after the cuff run produced something the earlier runs
did not: both settings landed at a level near 51,000, including the one that had sat at 4,500 all
through the cuff comparison. The gain register differed too - 4a09 throughout the cuff run, then
7e4b and 549c.

So the level is not something 0x0084 sets. It is where our gain search happens to stop, and
0x0084 only influences that. Which explains the three level groups in the archive, and it explains
the drift: nothing in our loop holds the level anywhere, so two measurements with the same
register can finish in different regimes.

The vendor does not have this problem because they regulate a different quantity. Their gain
register 0x0118 is 0x2828 in all three configurations - they do not vary it between settings at
all - and what they check is the level, against an explicit window per mode. Ours walks 0x0118
proportionally towards a target amplitude and never looks at the level.

Targeting amplitude is why the level wanders. Amplitude and level move together only while
everything else holds still; a change in contact or perfusion moves one without the other, and the
loop follows the amplitude wherever that goes.

Those captures are also useless as measurements, and the confidence number said so before any of
this analysis: purity 0.053 to 0.075 against a validated noise score of 0.073, with the peak at
0.55 to 0.75 Hz - baseline wander from a moving arm, not a pulse. motion read 59 to 83 against 0
during the cuff run. That is the first time the metric has earned its place: it rejected four
recordings that would otherwise have been analysed as though they meant something.

## The breath hold, and the regression it exposed

A hundred seconds of continuous capture: breathe, hold, breathe. The hold is in the record - the
respiratory peak between 0.12 and 0.45 Hz falls to 10 to 17 around t=45 to 55 where it is 20 to 45
either side.

R did not respond, and sat at 1.5 to 2.0, which is off the end of any calibration curve. The
reading taken from that was that the two channels must be the same wavelength, since they carried
nearly the same DC and a nearly constant AC ratio. That was wrong twice over: this file already
records that the pair is red and infrared, and the wearer can see the sensor go red in saturation
mode and green in rate mode.

The cause was the gain floor added earlier the same day. It raises gain until the weaker channel
clears a level, and on this sensor the red channel is meant to sit dim - about 3,500 of level and
four or five counts of pulse against infrared's sixty-four, which is exactly what these notes
already described. Lifting it pulls both channels to about 45,000 where they converge, and a ratio
between two channels reading the same thing is not a ratio. Measured directly:

    with the floor      level 46,074 / 44,824   ac 41 / 21   R 1.855
    without it          level  3,514 / 39,902   ac  4 / 68   R 0.736

So the floor now runs for the rate only. The vendor's own thresholds said as much and were
misread: their saturation floor is 5,111 against 28,626 for the rate, which is nearly no floor at
all, because for a ratio the channel that matters is the dim one.

Restored, three runs: ac 5/64 and 8/132, R of 0.882 and 0.738 - a saturation in the mid nineties.

Two corrections follow from this. The claim that the 47,600 counts between the channels was an
artefact of low gain rather than a real separation was wrong; it is real and the measurement needs
it. And the breath hold did not fail because of the sensor - it failed because of a change made a
few hours earlier, and the capture was good enough to have shown a desaturation if there had been
one to show.

## The red drive lever is still not found, and it is what blocks saturation

The second breath hold worked as an experiment. The hold is in the record twice over - respiratory
energy spikes to 105 at t=40 to 55 against 12 to 38 elsewhere, and the rate falls to 42 to 44 bpm
from 52 to 58 either side, which is breath-hold bradycardia. The wearer did it and the sensor saw
it.

R still could not report on it, and the reason is arithmetic rather than physiology. The red
channel carries one to three counts of pulse. R is (redAC/redLevel) over (irAC/irLevel), so half a
count of quantisation error on a two count numerator is twenty-five percent on R - which is the
entire scatter observed, 1.15 to 1.87 across one recording. A few percent of desaturation moves R
by about five percent. The measurement cannot see it, and no calibration curve changes that: a
curve applied to a ratio built on a two count numerator returns a number with twenty-five percent
of noise on it.

So the lever is the red LED drive, and sweeping the slot registers did not find it. Across the
writable range of the low byte in 0x0130, 0x0132 and 0x0134:

    current   red DC   red AC   IR DC
    0x06       3,865      3     45,356
    0x26       3,854      3     45,166
    0x76       3,849      3     45,121

Neither channel's DC moves. LED current would move it enormously. Two bits of that byte are not
writable either - bit 7 and bit 3 read back zero - so the field is narrower than it looks.

This file already said "per-slot LED current is the obvious lever and has not been found". It is
still not found, and this is a second failure to find it rather than a new one. What is new is
knowing what it costs: it is the whole of the saturation measurement, not a refinement of it.

## The manufacturer's register block, and a false result from it

Their three configurations write fifteen registers, and 0x0130, 0x0132 and 0x0134 - the slot
registers this project has twice recorded as the lever it could not find - are not among them.
Their LED block is 0x0080=0x0405, 0x0082=0x01c4 and 0x0084, which is the one they step through
0x21, 0x22 and 0x24 for their level ladder.

0x0084 alone, over our own sequence, collapses both FIFO channels onto one slot: dc1 equals dc2 to
the count, ac1 equals ac2, and R comes out 1.005 - one signal divided by itself. It means nothing
without the 0x0080 and 0x0082 that belong with it.

Applying all fifteen appeared to raise the red pulse from three counts to five hundred and twenty
three, and that was recorded here as the lever finally found. It was not. The raw samples say so:
both channels span the same 61,000 counts, from 3,149,505 to 3,210,597, and each carries a
within-group standard deviation of 17,662 across the record. A pulse is tens of counts on a
steady baseline, not seventeen thousand.

What that amplitude actually was is our own gain loop fighting their configuration. Their block
puts the DC above 3,200,000, which is this program's back-off threshold, so the gain steps down
again and again through the measurement and the resulting sweep is read as an enormous pulse.

Freezing the gain does not rescue it either: their block with our gain held gives levels of 56,862
and 64,805, both at or near the rail the notes record at about 3,210,580, and R of 1.427.

So the position is unchanged from before the attempt. The red channel carries one to three counts
of pulse under our own configuration, that is too small to build a ratio on, and their block has
not been made to work on our stack. The lever is still not found. What is new is only a way of
being wrong about it - a large amplitude that is the gain moving rather than blood.

## The blood pressure offset was not an offset

Ours read 116/72 and 113/70 against a cuff of 105/65, which looked like a systematic nine over six
and was worth chasing. Twenty minutes later the cuff read 120/74, 116/71, 115/72 and 112/66, and
ours read 112/69.

So the cuff moved about seventeen systolic within the hour and ours did not move at all: 112, 113,
114 and 116 across every measurement of the session, whatever the reference was doing. There is no
offset. There are two numbers drifting independently, and reading their difference at one moment
as a calibration error was a mistake about what was varying.

What that does show is worse than an offset. Ours is insensitive to real change - it sits near 114
whether the wearer is at 105 or at 120 - which for a measurement whose entire purpose is to notice
a change is the more serious fault, and one an offset correction would have hidden.

Instrumenting peaks found against beats kept says where the noise is not. Detection finds 34 to 37
peaks against about thirty-eight available, so no beats are being missed. Retention is 22 to 29 of
those when the wearer is still and drops to 4 when motion reads 283 - which is the shape test
doing its job, not throwing measurements away. An earlier reading of this - that the analysis
discards more than half the beats - came from runs that were all high-motion.

The noise is in the features themselves, and the arithmetic is the whole story. ai ranges 0.6 to
1.2 and carries a coefficient of 11.0, so it contributes about three and a half mmHg on its own.
sut ranges 150 to 291 ms against a coefficient of -0.055, which is another eight. Together that is
more than the seventeen of real change the cuff showed across the session, which is why ours sits
near 114 whatever the wearer is doing.

A claim that ai depends on the sample rate does not survive either. It was 1.21 to 1.23 at 198 Hz
against 0.61 to 1.01 at 100, which looked like a rate effect; later runs at 100 Hz gave 1.22 and
1.17. Same rate, 0.61 to 1.22. It is noisy, not rate-dependent, and two samples that happened to
differ were enough to build a mechanism on.

## The sut variance is occasional gross error, not jitter

Two suspects ruled out by measurement rather than argument. Spacing the foot's second difference
over a fixed twenty milliseconds instead of adjacent samples produced three more measurements and
made them noisier - mean consecutive change 46.1 ms against 36.4, worst 181 against 141. Removing
the per-beat alignment was worse still: 21 measurements of 43 instead of 36, and 46.0 ms of
change. The alignment is helping and the foot is not the problem.

Splitting one continuous recording into four 25-second segments - same wrist, same minutes, no
gap - says what is:

    segment  bpm  beats  sut  ai
    1         49    17   150  1.05
    2         52    17   231  1.14
    3         60    12   241  1.11
    4         54    15   231  1.13

Three agree within ten milliseconds and one is eighty-one adrift. So sut is not continuously
noisy; it is occasionally grossly wrong, about one measurement in four. That matches the shape of
the archive statistics, where the median change between consecutive recordings is a healthy 30 ms
while the mean is 36 and the worst 141 - outliers dragging an otherwise steady number.

The two bad segments are also the two with the worst rate: 49 and 60 against a true rate near 53.
The ensemble is built on the assumed period, so a wrong rate makes a wrong-length window and the
shape measured inside it is wrong with it. The pulse shape path inherits every rate error rather
than failing independently of one, which means sut cannot be stabilised on its own - the rate has
to be right first, and one rate estimate in four is not.

## 0x0080=0x0105 gives both channels a real pulse

Sweeping the high byte of 0x0080, which is in the vendor's block and not in our sequence:

    0x0080    ch1 amp   ch2 amp
    0x0105     141.6     119.4
    0x0205      91.1       0.0
    0x0305      13         5
    0x0405       8.3     102.4    <- ours and theirs
    0x0605       8.9     108.2

0x0105 is the only setting where both channels carry a comparable pulse. Captured and measured
against the DC as it actually reads, with no pedestal assumed, the modulations are 0.18 and 0.09
percent - both textbook for a PPG, where our usual configuration leaves red at three counts and an
impossible thirteen percent.

R across three consecutive runs is 2.073, 2.011 and 2.191: mean 2.09, spread 0.18. Stable to nine
percent, against the twenty-five it carried when the numerator was three counts of quantisation.

A single earlier capture gave 0.62 and was recorded here as the first credible ratio this project
had produced. It was not. That capture's ch2 held an excursion of a million counts against a mean
of 2.18 million, which inflated its peak-to-peak, which inflated its modulation to 0.31 percent
against the 0.09 that reproduces. One measurement, an artefact in it, and it was written up before
being repeated.

2.09 is not physiological. Inverted it is 0.48, which would be - but which channel carries red in
this configuration has not been established, and choosing the assignment that returns a plausible
answer is assuming what is being measured. It stays 2.09 until the assignment is shown.

## R is a measurement when there is amplitude, and noise when there is not

Two groups of runs on one wrist within the hour, same configuration:

    infrared amplitude 6 to 10      R = 1.68, 1.75, 1.80, 2.05, 2.06
    infrared amplitude 32 to 253    R = 0.61 to 0.81 over ten runs, mean about 0.70, spread 7%

The first group is not a poor measurement of saturation; it is not a measurement. Half a count of
rounding on an eight count amplitude is six percent before anything physiological happens, and a
ratio of two such numbers goes wherever the rounding sends it. The second group is stable and sits
where a healthy wearer should.

So the earlier conclusion here - that R is quantisation and no averaging fixes it - was drawn from
the low-amplitude runs alone and is too broad. It is quantisation when the amplitude is low, and
that is a condition worth testing rather than a property of the sensor.

ppgd now flags a ratio taken on too little pulse as weak=1, at an infrared amplitude of thirty,
drawn between the two groups rather than derived from anything. It wants revisiting when there are
runs in between to place it by, and it errs generous: a refused good measurement costs one
reading, an accepted bad one costs a saturation that is wrong without looking wrong.

The vendor has this check and we did not. Their configuration expects the dim channel around a
level of 5,111 and they test it every window - which is also why they can work with a red channel
this faint, where the effort here went into trying to make it brighter.

### How the amplitude is estimated, which is the real difference

Ours takes one sample per beat: the peak, minus an interpolated baseline. Theirs is an RMS across
the window - FUN_00022928 squares, sums and takes a root. On an eight count pulse a single sample
carries half a count of rounding and averages nothing; an RMS over four thousand samples averages
the same rounding down by sixty-odd.

The Goertzel amplitudes this file already computes for band_amp are the same quantity as their
RMS, and the ratio pass already reports its R from those, which is why that path gives 0.61 to
0.81 where the beatwise one gives 1.7 to 2.1 on the same wrist.

## Breath-hold calibration does not work on a wrist, for a physiological reason

The plan was sound on paper: R needs tying to a saturation somewhere, a reference oximeter is not
available, and a breath hold supplies a second point for nothing. Rest gives the intercept, the
hold gives the slope.

What a 115-second capture shows, in twenty-second windows:

    t(s)   ac1   ac2      R    resp
     0-35  84-30 187-63  0.69-0.84  293-435
    40-85  12-6   21-5   1.0-2.0    172-5     <- the hold
    90-95  18-31  32-58  0.96-0.98  224-379

The hold is unmistakable - respiratory energy falls from 435 to 5 - and so is the problem. The
pulse amplitude collapses with it, from 187 counts to five, which is well inside the range where R
is quantisation rather than measurement. Every window during the hold is weak. There is no
saturation to read out of them.

The cause is the wearer, not the sensor. Holding a breath provokes the diving response and with it
peripheral vasoconstriction, so wrist perfusion falls exactly when the saturation does. On a
fingertip clip the amplitude has room to spare and survives it; on a wrist it does not.

That also settles the earlier attempt at this, where the amplitude collapse was read as the sensor
losing contact and the wearer was asked to sit still and repeat it. They were sitting still. It
was their circulation.

The analysis script will fit a line through it if allowed - it reported a slope of -21.6 percent
per unit R from this recording - and that number is worth nothing: the windows it used were the
two transitions either side of the hold, the only ones near the hold with any pulse in them. A
calibration from a breath hold needs the amplitude to survive the hold, and here it does not.

## Wrist perfusion is intermittent, and that is what blocks a calibration

Across ninety seconds on one wrist, without moving the watch or changing anything:

    ac1    ac2      R        note
   854.2  1475.4   0.611
     8.2     2.1   1.651     weak
    84.7   198.5   0.713
     6.6     7.0   1.604     weak
     7.5     8.3   1.520     weak
     4.2     8.0   0.891     weak

dc1 sits at 3,162,7xx through all of it, so the light reaching the sensor does not change. Only the
modulation does, by a factor of seven hundred.

The shape of it is consistent. When perfusion is good the two channels differentiate as a red and
an infrared should - 854 against 1475, or 84.7 against 198.5 - and R lands at 0.61 to 0.71. When it
is not, both sit at six to eight counts, which is the same in each because it is not a pulse in
either, and R goes wherever the rounding sends it.

That is what blocks the calibration rather than anything about the algorithm. A breath hold needs a
stable baseline to measure a fall from, and a baseline that moves seven hundredfold inside two
minutes is not one. The hold then has to survive on top of that, and holding a breath constricts
the periphery further.

Warm hands help and are not sufficient: the two strong readings above came minutes after warming
and lasted about a minute between them. What would settle it is a fingertip reference alongside,
where the amplitude is ten to fifty times larger and survives both the hold and this.
