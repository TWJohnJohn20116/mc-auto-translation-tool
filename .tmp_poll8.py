import io, json, subprocess, sys, time
R = "TWJohnJohn20116/mc-auto-translation-tool"
def run_json(rid):
    p = subprocess.run(["gh","run","view",str(rid),"-R",R,"--json","status,conclusion,jobs,headSha,url,workflowName"],
                       capture_output=True, text=True, encoding="utf-8")
    if p.returncode != 0 or not p.stdout.strip():
        return None
    return json.loads(p.stdout)
runs = [38114424455, 38114455346]
deadline = time.time() + 3000
while time.time() < deadline:
    states = []
    done = True
    for rid in runs:
        d = run_json(rid)
        if d is None:
            states.append((rid, "unknown", None)); done = False; continue
        states.append((rid, d.get("status"), d.get("conclusion")))
        if d.get("status") != "completed":
            done = False
    print("poll:", states, flush=True)
    if done:
        break
    time.sleep(45)
for rid in runs:
    d = run_json(rid)
    if d is None:
        print("RUN %d: could not read" % rid); continue
    jobs = d.get("jobs") or []
    skipped = [j["name"] for j in jobs if j.get("conclusion") == "skipped"]
    bad = [(j["name"], j.get("conclusion")) for j in jobs if j.get("conclusion") not in ("success", "skipped", None)]
    print("=" * 70)
    print("RUN %d  %s  head=%s" % (rid, d.get("workflowName"), (d.get("headSha") or "")[:8]))
    print("  url        : %s" % d.get("url"))
    print("  status     : %s / %s" % (d.get("status"), d.get("conclusion")))
    print("  jobs       : %d  success=%d  skipped=%d  other=%d" % (
        len(jobs), sum(1 for j in jobs if j.get("conclusion") == "success"),
        len(skipped), len(bad)))
    if skipped: print("  SKIPPED    : %s" % skipped)
    if bad:     print("  FAILED     : %s" % bad)
