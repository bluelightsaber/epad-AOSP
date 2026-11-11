#!/usr/bin/env python3
"""Lista los trozos de una imagen sparse de Android y, con DIR, extrae los RAW/FILL a ficheros DIR/c<bloque>.bin.
Uso: sparse-trozos.py IMG [DIR]"""
import struct, sys, os
f = open(sys.argv[1], 'rb'); out = sys.argv[2] if len(sys.argv) > 2 else None
magic, maj, mn, fhs, chs, bs, tb, tc, crc = struct.unpack('<IHHHHIIII', f.read(28))
assert magic == 0xed26ff3a
f.seek(fhs); blk = 0
for i in range(tc):
    t, _, n, tot = struct.unpack('<HHII', f.read(chs)); f.seek(chs - 12, 1) if chs > 12 else None
    dl = tot - chs
    kind = {0xCAC1: 'RAW', 0xCAC2: 'FILL', 0xCAC3: 'DONTCARE', 0xCAC4: 'CRC'}[t]
    data = f.read(dl)
    extra = ''
    if kind == 'FILL':
        extra = ' valor=%08x' % struct.unpack('<I', data)[0]
    print('%-8s bloque=%8d n=%8d (%.1f MB)%s' % (kind, blk, n, n * bs / 1e6, extra))
    if out and kind in ('RAW', 'FILL'):
        with open(os.path.join(out, 'c%d.bin' % blk), 'wb') as o:
            if kind == 'RAW':
                o.write(data)
            else:
                for _ in range(n):
                    o.write(data * (bs // 4))
    if kind != 'CRC':
        blk += n
print('total bloques', blk, 'de', tb, 'bs', bs)
