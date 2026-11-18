import glob, json, os
from collections import defaultdict
d = "/build/workspace/edap/edap/target/crypto-perf"
s = defaultdict(dict)
for p in sorted(glob.glob(d + "/*.json")):
  r = int(os.path.basename(p).rsplit("-r", 1)[1].split(".")[0])
  for e in json.load(open(p)):
      n = e["benchmark"].rsplit(".", 1)[-1]
      pay = (e.get("params") or {}).get("payloadSize")
      s[(n, pay)][r] = e["primaryMetric"]["score"]
for k in sorted(s):
  if k[0].endswith("16t"):
      v = s[k]
      print("%-16s %-6s :" % (k[0], k[1]), "  ".join("%9.0f" % v.get(r, 0) for r in sorted(v)))