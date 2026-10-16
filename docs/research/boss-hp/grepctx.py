import glob, re, sys, os
pat = re.compile(sys.argv[1], re.I)
skip = set(sys.argv[2:])
for fn in sorted(glob.glob("wt/*.txt")):
    name = os.path.basename(fn)[:-4]
    if name in skip: continue
    t = open(fn).read()
    for m in pat.finditer(t):
        s = t[max(0, m.start()-200): m.end()+220].replace("\n", " ")
        print(f"[{name}] ...{s}...")
