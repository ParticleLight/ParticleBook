import re, os, glob, csv
base = "spikes/spike2-results"
raw = os.path.join(base, "raw"); csvo = os.path.join(base, "csv")
os.makedirs(csvo, exist_ok=True)
pat = re.compile(r"PBPERF\|scenario=(?P<scenario>[^|]*)\|phase=(?P<phase>[^|]*)\|page=(?P<page>[^|]*)\|w=(?P<w>[^|]*)\|h=(?P<h>[^|]*)\|ms=(?P<ms>[^|]*)\|extra=(?P<extra>.*)")
dpat = re.compile(r"PBDONE\|scenario=(?P<scenario>[^|]*)\|runs=(?P<runs>[^|]*)\|extra=(?P<extra>.*)")
tpat = re.compile(r"PBTIME\|app=(?P<app>[^|]*)\|phase=(?P<phase>[^|]*)\|ms=(?P<ms>[^|]*)")
rows, done, times = [], [], []
files = sorted(glob.glob(os.path.join(raw, "tiles-dev-*.log")))
for f in files:
    run = os.path.basename(f)[len("tiles-dev-"):-4]
    for line in open(f, encoding="utf-8", errors="replace"):
        m = pat.search(line)
        if m:
            d = m.groupdict(); d["run"] = run; rows.append(d); continue
        m = dpat.search(line)
        if m:
            d = m.groupdict(); d["run"] = run; done.append(d); continue
        m = tpat.search(line)
        if m:
            d = m.groupdict(); d["run"] = run; times.append(d)
def w(name, fields, data):
    p = os.path.join(csvo, name)
    with open(p, "w", newline="", encoding="utf-8") as fh:
        wr = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
        wr.writeheader()
        for r in data: wr.writerow(r)
    print(f"{name}: {len(data)} rows")
w("pbperf.csv", ["run","scenario","phase","page","w","h","ms","extra"], rows)
w("pbdone.csv", ["run","scenario","runs","extra"], done)
w("pbtime.csv", ["run","app","phase","ms"], times)
print("runs:", ", ".join(sorted({r["run"] for r in rows})))
