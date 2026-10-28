#!/usr/bin/env bash
# Regenerates packaging/icons/jobpilot.{png,ico,icns} from jobpilot.svg (needs rsvg-convert and python3).
set -euo pipefail
cd "$(dirname "$0")/icons"
for s in 16 32 48 64 128 256 512 1024; do rsvg-convert -w $s -h $s jobpilot.svg -o "/tmp/jobpilot-$s.png"; done
cp /tmp/jobpilot-512.png jobpilot.png
python3 - <<'PY'
import struct
def png(s): return open(f"/tmp/jobpilot-{s}.png","rb").read()
# .ico with embedded PNGs (Windows Vista and later)
sizes=[16,32,48,64,128,256]
data=[png(s) for s in sizes]
out=struct.pack("<HHH",0,1,len(sizes)); off=6+16*len(sizes); body=b""
for s,d in zip(sizes,data):
    out+=struct.pack("<BBBBHHII", 0 if s==256 else s, 0 if s==256 else s, 0,0,1,32,len(d),off+len(body)); body+=d
open("jobpilot.ico","wb").write(out+body)
# .icns with PNG payloads
types=[(b"icp4",16),(b"icp5",32),(b"icp6",64),(b"ic07",128),(b"ic08",256),(b"ic09",512),(b"ic10",1024)]
chunks=b"".join(t+struct.pack(">I",8+len(png(s)))+png(s) for t,s in types)
open("jobpilot.icns","wb").write(b"icns"+struct.pack(">I",8+len(chunks))+chunks)
PY
ls -la jobpilot.*
