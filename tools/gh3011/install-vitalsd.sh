#!/system/bin/sh
# Put our vitals daemon in the vendor daemon's init slot.
#
# init already runs gh3011_service as root and restarts it if it dies, which is exactly the
# supervision this needs and is not otherwise available to us: the app has no root, and nothing
# else here survives an adb disconnect. The real vendor binary is kept alongside as .real so the
# original behaviour is one copy away.
mount -o rw,remount /system 2>/dev/null
setprop ctl.stop gh3011_daemon
sleep 2
if [ ! -f /system/bin/gh3011_service.real ]; then
    cat /system/bin/gh3011_service > /system/bin/gh3011_service.real
    chmod 755 /system/bin/gh3011_service.real
fi
cat > /system/bin/gh3011_service <<'W'
#!/system/bin/sh
# Our vitals daemon, in the slot init used to start the vendor's.
# To go back: cat /system/bin/gh3011_service.real > /system/bin/gh3011_service
exec /data/local/tmp/vitalsd
W
chmod 755 /system/bin/gh3011_service
setprop ctl.start gh3011_daemon
sleep 3
echo "--- what init is running now:"
ps | grep -E "vitalsd|gh3011"
echo "--- files:"
ls -l /system/bin/gh3011_service /system/bin/gh3011_service.real
