"""Summarizes power_<label>.csv files written by tools/power_sample.sh.

Average drain comes from a least-squares slope of the charge counter (µAh) over time, which is far
more precise than battery-% steps. CPU is reported as % of one core (100 % = one core fully busy),
assuming USER_HZ = 100 jiffies/s. Usage: python3 tools/analyze_power.py power_*.csv
"""
import csv
import sys

HZ = 100.0
PROCS = ["facebridge", "vrchat", "pxreyetracking", "pvrtracking"]


def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(l for l in f if not l.startswith("#")):
            rows.append({k: float(v) for k, v in r.items() if v not in ("", None)})
    return rows


def slope(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else 0.0


def summarize(path):
    rows = load(path)
    if len(rows) < 3:
        return None
    t = [r["time"] for r in rows]
    dt = t[-1] - t[0]
    drain_ma = -slope(t, [r["charge_uah"] for r in rows]) * 3600 / 1000   # µAh/s → mA
    out = {
        "label": path.split("power_")[-1].rsplit(".", 1)[0],
        "minutes": dt / 60,
        "drain_mA": drain_ma,
        "level": f"{rows[0]['level']:.0f}→{rows[-1]['level']:.0f}",
        "temp_C": f"{rows[0]['temp_dc']/10:.1f}→{rows[-1]['temp_dc']/10:.1f}",
        "cpu_busy": (rows[-1]["cpu_busy"] - rows[0]["cpu_busy"]) / (dt * HZ) * 100 if "cpu_busy" in rows[0] else float("nan"),
        "charging": any(r["status"] == 2 for r in rows),
    }
    for p in PROCS:
        out[p] = (rows[-1][p] - rows[0][p]) / (dt * HZ) * 100 if p in rows[0] else float("nan")
    return out


def main(paths):
    res = [s for s in (summarize(p) for p in paths) if s]
    hdr = f"{'scenario':<14}{'min':>5}{'drain mA':>10}{'level':>9}{'temp °C':>13}{'all busy':>11}" + "".join(f"{p:>15}" for p in PROCS)
    print(hdr)
    print("-" * len(hdr))
    for s in res:
        line = f"{s['label']:<14}{s['minutes']:>5.1f}{s['drain_mA']:>10.0f}{s['level']:>9}{s['temp_C']:>13}{s['cpu_busy']:>10.0f}%"
        line += "".join(f"{s[p]:>14.1f}%" for p in PROCS)
        if s["charging"]:
            line += "   (!) charging during run"
        print(line)
    print("\nCPU columns: % of one core ('all busy' = whole system, 800 % = all 8 cores busy).")


if __name__ == "__main__":
    main(sys.argv[1:])
