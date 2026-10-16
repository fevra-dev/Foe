import re, sys
pat = re.compile(sys.argv[1], re.I)
excl = re.compile(r"\bdrops?\b|\bloot|\breward|\bpet\b|\bclue", re.I)
for fn in sys.argv[2:]:
    t = open(fn).read()
    n = 0
    print("==", fn[3:-4])
    for m in pat.finditer(t):
        s = t[max(0, m.start()-150): m.end()+170].replace("\n", " ")
        if excl.search(s): continue
        print("   ", s); n += 1
        if n >= 4: break
