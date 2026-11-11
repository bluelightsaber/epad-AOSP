#!/usr/bin/env python3
"""GPT de la eMMC con particiones para AOSP (E-PAD).

Uso: gpt-epad.py PRIMARIA(34 sectores) SECUNDARIA(33 sectores) SALIDA_PRIMARIA SALIDA_SECUNDARIA
Reduce userdata (entrada 29) a USERDATA_GIB y añade al final de la tabla epad_metadata, super y epad_data hasta
flashinfo. Las entradas 1-30 conservan índice, nombre, tipo, GUID y atributos (los números mmcblk0pN no cambian).
"""
import struct, sys, uuid, zlib

SECT = 512
USERDATA_GIB = 8
NEW = [("epad_metadata", 16 << 20), ("super", 6 << 30), ("epad_data", None)]

def name_of(e):
    return e[56:128].decode("utf-16-le").rstrip("\0")

def build(prim, sec):
    hdr = bytearray(prim[SECT:2 * SECT])
    (sig, rev, hsize, _crc, _r, cur, bak, first, last) = struct.unpack("<8sIIIIQQQQ", hdr[:56])
    ent_lba, n, esz, _ecrc = struct.unpack("<QIII", hdr[72:92])
    assert sig == b"EFI PART" and cur == 1 and ent_lba == 2 and n == 128 and esz == 128
    ents = bytearray(prim[2 * SECT:2 * SECT + n * esz])
    assert zlib.crc32(ents) == _ecrc, "CRC de entradas de la primaria"
    used = [i for i in range(n) if any(ents[i * esz:i * esz + 16])]
    names = [name_of(ents[i * esz:(i + 1) * esz]) for i in used]
    assert used == list(range(30)) and names[28] == "userdata" and names[29] == "flashinfo", names
    def ent(i):
        return ents[i * esz:(i + 1) * esz]
    ud = ent(28)
    ud_first, ud_last = struct.unpack("<QQ", ud[32:48])
    fi_first = struct.unpack("<Q", ent(29)[32:40])[0]
    assert ud_last + 1 == fi_first
    new_ud_last = ud_first + (USERDATA_GIB << 30) // SECT - 1
    struct.pack_into("<Q", ents, 28 * esz + 40, new_ud_last)
    typ = bytes(ud[0:16])
    lba = new_ud_last + 1
    plan = []
    for k, (nm, size) in enumerate(NEW):
        start = (lba + 2047) // 2048 * 2048            # 1 MiB
        end = fi_first - 1 if size is None else start + size // SECT - 1
        assert end < fi_first and end >= start
        e = bytearray(esz)
        e[0:16] = typ
        e[16:32] = uuid.uuid4().bytes_le
        struct.pack_into("<QQQ", e, 32, start, end, 0)
        e[56:56 + 2 * len(nm)] = nm.encode("utf-16-le")
        ents[(30 + k) * esz:(31 + k) * esz] = e
        plan.append((nm, start, end))
        lba = end + 1
    ecrc = zlib.crc32(ents)
    def header(cur_lba, other, entries_lba):
        h = bytearray(hdr)
        struct.pack_into("<QQ", h, 24, cur_lba, other)
        struct.pack_into("<Q", h, 72, entries_lba)
        struct.pack_into("<I", h, 88, ecrc)
        struct.pack_into("<I", h, 16, 0)
        struct.pack_into("<I", h, 16, zlib.crc32(h[:hsize]))
        return h + bytes(SECT - len(h))
    new_prim = bytearray(prim)
    new_prim[SECT:2 * SECT] = header(1, bak, 2)
    new_prim[2 * SECT:2 * SECT + n * esz] = ents
    # secundaria: 32 sectores de entradas + cabecera en el último sector (bak)
    assert len(sec) == 33 * SECT
    sec_hdr = sec[32 * SECT:]
    assert sec_hdr[:8] == b"EFI PART" and struct.unpack("<Q", sec_hdr[24:32])[0] == bak
    new_sec = bytearray(ents) + header(bak, 1, bak - 32)
    return bytes(new_prim), bytes(new_sec), (ud_first, ud_last, new_ud_last, fi_first, plan, bak)

if __name__ == "__main__":
    prim = open(sys.argv[1], "rb").read()[:34 * SECT]
    sec = open(sys.argv[2], "rb").read()[-33 * SECT:]
    p, s, info = build(prim, sec)
    ud_first, ud_last, new_ud_last, fi_first, plan, bak = info
    gib = lambda a, b: (b - a + 1) * SECT / 2**30
    print(f"userdata {ud_first}-{ud_last} ({gib(ud_first, ud_last):.2f} GiB) -> {ud_first}-{new_ud_last} ({gib(ud_first, new_ud_last):.2f} GiB)")
    for i, (nm, a, b) in enumerate(plan):
        print(f"p{31 + i} {nm}: {a}-{b} ({gib(a, b):.2f} GiB)")
    print(f"flashinfo empieza en {fi_first}; cabecera secundaria en {bak}")
    open(sys.argv[3], "wb").write(p)
    open(sys.argv[4], "wb").write(s)
