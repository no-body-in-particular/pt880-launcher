#!/system/bin/sh
# Which drive setting is actually more accurate, measured against a cuff.
#
# Their level ladder moves one register, 0x0084, and setting it decides which channel the shared
# gain favours: 0x21 puts the level near 4,500 and 0x22 near 48,300. Six measurements on the wrist
# showed the two agreeing to about a bpm, which retired an earlier claim that the dim one was five
# bpm better - but agreeing with each other is not the same as being right, and nothing so far has
# compared either against a reference.
#
# So: alternate the two settings, four measurements each, while a cuff is on the other arm. The
# alternation matters. Running four of one and then four of the other would let a drifting pulse
# masquerade as a difference between the settings, and a resting pulse drifts several bpm over the
# few minutes this takes.
#
# Forty seconds each, which is what the earlier runs used and long enough that both settings
# produce an answer - at twenty five seconds 0x22 returned no_agreement and it would have looked
# like the setting failing rather than the window being short.
#
# Run it, take cuff readings while it runs, and note roughly when each one was taken. Total is
# about six minutes.

PPGD=/data/local/tmp/ppgd
SECS=40

echo "stopping the vitals daemon to take the sensor"
setprop ctl.stop gh3011_daemon
sleep 2

echo ""
echo "eight measurements, alternating, about ${SECS}s each - take cuff readings throughout"
echo ""

i=1
while [ $i -le 4 ]; do
    for v in 0021 0022; do
        echo "--- pair $i, 0084=$v ---"
        $PPGD $SECS "" spo2 0084=$v 2>&1
        echo ""
    done
    i=$((i + 1))
done

echo "giving the sensor back"
setprop ctl.start gh3011_daemon
sleep 2
echo "daemon: $(getprop init.svc.gh3011_daemon)"
