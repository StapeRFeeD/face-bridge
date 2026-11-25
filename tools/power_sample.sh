#!/system/bin/sh
# On-device power/CPU sampler for Face Bridge measurements (runs on the headset, no PC needed).
#
# Start (over USB or adb-over-Wi-Fi), then the cable/Wi-Fi can be disconnected:
#   adb push tools/power_sample.sh /data/local/tmp/
#   adb shell "nohup sh /data/local/tmp/power_sample.sh <label> <seconds> <interval> >/dev/null 2>&1 &"
# Collect later:
#   adb pull /data/local/tmp/power_<label>.csv
#
# Battery sysfs is not readable by adb shell on PICO OS 5, so battery values come from
# `dumpsys battery`: level %, charge counter (µAh remaining — its slope gives average drain in mA),
# voltage mV, temperature 0.1 °C, status (2 charging, 3 discharging).
# CPU: total jiffies from /proc/stat, then utime+stime jiffies of Face Bridge, VRChat,
# pxreyetrackingservice and pvrtrackingservice (PICO tracking daemons).

LABEL=${1:-run}
DURATION=${2:-300}
INTERVAL=${3:-5}
OUT=/data/local/tmp/power_${LABEL}.csv

battery() {
  dumpsys battery | awk -F': ' '
    /^  level:/ {l=$2} /Charge counter/ {c=$2} /^  voltage:/ {v=$2} /^  temperature:/ {t=$2} /^  status:/ {s=$2}
    END {printf "%s,%s,%s,%s,%s", l, c, v, t, s}'
}

# Sum utime+stime (fields 14,15) of all processes whose cmdline contains $1.
proc_jiffies() {
  total=0
  for p in $(pgrep -f "$1" 2>/dev/null); do
    s=$(cat /proc/$p/stat 2>/dev/null) || continue
    # strip "pid (comm) " so field positions are stable even if comm has spaces
    rest=${s#*) }
    set -- $rest
    total=$((total + ${12} + ${13}))
  done
  echo $total
}

echo "time,level,charge_uah,voltage_mv,temp_dc,status,cpu_total,cpu_busy,facebridge,vrchat,pxreyetracking,pvrtracking" > "$OUT"
END=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$END" ]; do
  cpu=$(head -1 /proc/stat | awk '{s=0; for(i=2;i<=NF;i++) s+=$i; print s "," s-$5-$6}')
  echo "$(date +%s),$(battery),$cpu,$(proc_jiffies io.github.staperfeed.facebridge),$(proc_jiffies com.vrchat.android),$(proc_jiffies pxreyetrackingservice),$(proc_jiffies pvrtrackingservice)" >> "$OUT"
  sleep "$INTERVAL"
done
echo "# done" >> "$OUT"
