#!/system/bin/sh
# Find the thermometer.
#
# The vendor reads it through Android's sensor framework - com.ic.work has an onSensorChanged
# that converts a wrist reading to a body temperature - so it is registered as a sensor. What is
# not known is whether anything underneath the framework is readable directly, which is what
# wear detection in vitalsd would need: vitalsd is C, runs as root, and has no JVM.
#
# So dump every layer at once. One pass, because the watch is only reachable in short windows.

echo "=== sensors known to the framework"
dumpsys sensorservice 2>/dev/null | grep -iE "temp|therm|gxts|0x7|handle" | head -25

echo "=== input devices"
cat /proc/bus/input/devices 2>/dev/null

echo "=== thermal zones"
for z in /sys/class/thermal/thermal_zone*; do
    [ -d "$z" ] || continue
    echo "$z type=$(cat $z/type 2>/dev/null) temp=$(cat $z/temp 2>/dev/null)"
done

echo "=== hwmon"
for h in /sys/class/hwmon/hwmon*; do
    [ -d "$h" ] || continue
    echo "$h name=$(cat $h/name 2>/dev/null)"
    ls $h 2>/dev/null | head -10
done

echo "=== anything named like the part"
ls -d /sys/bus/i2c/devices/*/ 2>/dev/null
find /sys -iname "*gxts*" -o -iname "*temp*sensor*" 2>/dev/null | head -20

echo "=== i2c bus 2 (the PPG lives at 2-0014)"
ls /sys/bus/i2c/devices/ 2>/dev/null
