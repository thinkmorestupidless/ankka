#!/usr/bin/env python3
"""What a k3s suite's runner was short of, if anything.

`record DIR` samples the runner every 10 seconds until it is killed: CPU split into busy, iowait
and steal, the kernel's pressure stall figures (the share of time some task waited for CPU, memory
or IO), available memory, swap and free disk. Every 30 seconds it adds each container's CPU and
memory, and every 60 the k3s node's pods (`kubectl top`, which k3s's metrics-server answers once it
has been up a minute or so). `summary DIR` prints the run's peaks as Markdown, for the job's
summary, so a failed suite's runner can be compared with a passing one's.

Standard library only, and every read tolerates failure: the sampler must never be why a run fails.
"""

import csv
import json
import os
import statistics
import subprocess
import sys
import time

HOST = "host.csv"
CONTAINERS = "containers.csv"
PODS = "pods.txt"

HOST_FIELDS = [
    "time", "load1", "busy", "iowait", "steal",
    "cpu_some", "memory_some", "memory_full", "io_some",
    "mem_available_mib", "swap_used_mib", "disk_free_gib",
]


def read(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""


def cpu_times():
    fields = read("/proc/stat").splitlines()[0].split()[1:]
    return [int(x) for x in fields] if fields else []


def pressure(kind, line="some"):
    # "some avg10=1.23 avg60=... total=..." per line; avg10 is the last ten seconds, in percent.
    for row in read(f"/proc/pressure/{kind}").splitlines():
        if row.startswith(line):
            for part in row.split():
                if part.startswith("avg10="):
                    return float(part[6:])
    return ""


def meminfo():
    values = {}
    for row in read("/proc/meminfo").splitlines():
        name, _, rest = row.partition(":")
        values[name] = int(rest.split()[0]) // 1024 if rest.split() else 0
    return values


def run(*command, timeout=20):
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=timeout).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def record(directory):
    os.makedirs(directory, exist_ok=True)
    host = open(os.path.join(directory, HOST), "w", newline="")
    host_out = csv.writer(host)
    host_out.writerow(HOST_FIELDS)
    containers = open(os.path.join(directory, CONTAINERS), "w", newline="")
    containers_out = csv.writer(containers)
    containers_out.writerow(["time", "name", "id", "cpu_percent", "mem_mib"])
    pods = open(os.path.join(directory, PODS), "w")
    before = cpu_times()
    tick = 0
    while True:
        time.sleep(10)
        tick += 1
        now = int(time.time())
        after = cpu_times()
        row = [now, read("/proc/loadavg").split()[0] if read("/proc/loadavg") else ""]
        if before and after:
            delta = [a - b for a, b in zip(after, before)]
            total = sum(delta) or 1
            # user nice system idle iowait irq softirq steal
            idle, iowait, steal = delta[3], delta[4], delta[7] if len(delta) > 7 else 0
            row += [
                round(100 * (total - idle - iowait - steal) / total, 1),
                round(100 * iowait / total, 1),
                round(100 * steal / total, 1),
            ]
        else:
            row += ["", "", ""]
        before = after
        row += [pressure("cpu"), pressure("memory"), pressure("memory", "full"), pressure("io")]
        memory = meminfo()
        row += [
            memory.get("MemAvailable", ""),
            memory.get("SwapTotal", 0) - memory.get("SwapFree", 0),
        ]
        try:
            stat = os.statvfs("/")
            row.append(round(stat.f_bavail * stat.f_frsize / 2**30, 1))
        except OSError:
            row.append("")
        host_out.writerow(row)
        host.flush()

        if tick % 3 == 0:
            for line in run("docker", "stats", "--no-stream", "--format", "{{json .}}").splitlines():
                try:
                    stats = json.loads(line)
                    used = stats["MemUsage"].split("/")[0].strip()
                    containers_out.writerow([
                        now, stats["Name"], stats.get("ID", ""),
                        stats["CPUPerc"].rstrip("%"), mebibytes(used),
                    ])
                except (ValueError, KeyError):
                    pass
            containers.flush()

        if tick % 6 == 0:
            ids = run("docker", "ps", "--filter", "ancestor=rancher/k3s", "-q").split()
            ids = ids or [
                line.split()[0]
                for line in run("docker", "ps", "--format", "{{.ID}} {{.Image}}").splitlines()
                if "k3s" in line
            ]
            for node in ids:
                top = run("docker", "exec", node, "kubectl", "top", "pods", "-A", "--no-headers")
                if top:
                    pods.write(f"== {now} {node}\n{top}")
            pods.flush()


def mebibytes(text):
    units = {"B": 1 / 2**20, "KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "kB": 1 / 1024, "MB": 1, "GB": 1024}
    for unit in sorted(units, key=len, reverse=True):
        if text.endswith(unit):
            return round(float(text[: -len(unit)]) * units[unit])
    return ""


def numbers(rows, field):
    out = []
    for row in rows:
        try:
            out.append(float(row[field]))
        except (ValueError, KeyError):
            pass
    return out


def summary(directory):
    path = os.path.join(directory, HOST)
    if not os.path.exists(path):
        print("No resource samples were recorded.")
        return
    with open(path) as f:
        rows = list(csv.DictReader(f))
    if not rows:
        print("No resource samples were recorded.")
        return
    minutes = (int(rows[-1]["time"]) - int(rows[0]["time"])) / 60
    cores = os.cpu_count() or 0
    total = meminfo().get("MemTotal", 0)
    print(f"### Runner resources ({cores} CPUs, {total / 1024:.1f} GiB, {len(rows)} samples over {minutes:.0f} min)\n")
    print("| | median | p95 | max | samples at or above |")
    print("|---|---|---|---|---|")

    def line(label, field, threshold, unit=""):
        values = numbers(rows, field)
        if not values:
            print(f"| {label} | – | – | – | – |")
            return
        ordered = sorted(values)
        p95 = ordered[min(len(ordered) - 1, int(0.95 * len(ordered)))]
        share = 100 * sum(v >= threshold for v in values) / len(values)
        print(
            f"| {label} | {statistics.median(values):g}{unit} | {p95:g}{unit} | {max(values):g}{unit} "
            f"| {share:.0f}% at ≥ {threshold:g}{unit} |"
        )

    line("CPU busy", "busy", 95, "%")
    line("load (1 min)", "load1", 2 * cores)
    line("CPU pressure, some", "cpu_some", 50, "%")
    line("memory pressure, some", "memory_some", 10, "%")
    line("memory pressure, full", "memory_full", 5, "%")
    line("IO pressure, some", "io_some", 20, "%")
    line("iowait", "iowait", 20, "%")
    line("steal", "steal", 5, "%")
    available = numbers(rows, "mem_available_mib")
    swap = numbers(rows, "swap_used_mib")
    disk = numbers(rows, "disk_free_gib")
    print()
    if available:
        print(f"Least memory available: {min(available) / 1024:.1f} GiB. ", end="")
    if swap:
        print(f"Most swap used: {max(swap) / 1024:.1f} GiB. ", end="")
    if disk:
        print(f"Least disk free: {min(disk):g} GiB.", end="")
    print("\n")

    containers = os.path.join(directory, CONTAINERS)
    if os.path.exists(containers):
        with open(containers) as f:
            peaks = {}
            for row in csv.DictReader(f):
                try:
                    cpu, mem = float(row["cpu_percent"]), float(row["mem_mib"] or 0)
                except ValueError:
                    continue
                name = row["name"]
                old = peaks.get(name, (0.0, 0.0))
                peaks[name] = (max(old[0], cpu), max(old[1], mem))
        if peaks:
            print("| container | peak CPU (100% is one core) | peak memory |")
            print("|---|---|---|")
            for name, (cpu, mem) in sorted(peaks.items(), key=lambda p: -p[1][0])[:8]:
                print(f"| {name} | {cpu:.0f}% | {mem / 1024:.1f} GiB |")
            print()


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("record", "summary"):
        sys.exit("usage: cluster-sampler.py record|summary DIR")
    (record if sys.argv[1] == "record" else summary)(sys.argv[2])
