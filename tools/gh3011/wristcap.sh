#!/system/bin/sh
# On-wrist capture: tap the daemon's i2c, take one long measurement, restore everything.
#
# Usage: sh /data/local/tmp/wristcap.sh [seconds]   (default 90)
#
# Leaves /data/local/tmp/i2c.log (the daemon's i2c traffic) and /data/local/tmp/ev.txt (what the
# driver emitted). The restore runs unconditionally so the sensor is never left wrapped.
SECS=${1:-90}
mount -o rw,remount /system 2>/dev/null
chmod 755 /data/local/tmp/evread /data/local/tmp/sensorstart 2>/dev/null

setprop ctl.stop gh3011_daemon
sleep 3
cat /system/bin/gh3011_service > /system/bin/gh3011_service.real
chmod 755 /system/bin/gh3011_service.real
cat > /system/bin/gh3011_service <<'W'
#!/system/bin/sh
export LD_PRELOAD=/data/local/tmp/i2ctap.so
exec /system/bin/gh3011_service.real "$@"
W
chmod 755 /system/bin/gh3011_service
rm -f /data/local/tmp/i2c.log /data/local/tmp/ev.txt
setprop ctl.start gh3011_daemon
sleep 4
echo "--- wrapped daemon:"
ps | grep gh3011

echo "--- measuring for ${SECS}s, keep it on the wrist and still:"
/data/local/tmp/evread $((SECS + 6)) > /data/local/tmp/ev.txt 2>&1 &
/data/local/tmp/sensorstart $SECS 0x0086 > /dev/null 2>&1
sleep 3

echo "--- restoring:"
setprop ctl.stop gh3011_daemon
sleep 3
cat /system/bin/gh3011_service.real > /system/bin/gh3011_service
chmod 755 /system/bin/gh3011_service
rm -f /system/bin/gh3011_service.real
setprop ctl.start gh3011_daemon
sleep 2

echo "--- results:"
getprop init.svc.gh3011_daemon
ls -l /system/bin/gh3011_service*
ls -l /data/local/tmp/i2c.log /data/local/tmp/ev.txt
echo "--- events:"
cat /data/local/tmp/ev.txt
