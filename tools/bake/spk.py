"""Tiny writer for the SPK1 container used by the Android engine.

Layout (little endian):
    b"SPK1", int32 entry_count
    per entry: uint16 name_len, name (utf-8), uint8 kind, ...
        kind 0  float32 array: uint8 ndim, int32 dims[ndim], float32 data
        kind 1  string:        int32 byte_len, utf-8 bytes
        kind 2  float16 array: uint8 ndim, int32 dims[ndim], float16 data
"""
import struct
import numpy as np


class SpkWriter:
    def __init__(self):
        self.entries = []

    def array(self, name, value, half=False):
        a = np.ascontiguousarray(np.asarray(value, dtype=np.float16 if half else np.float32))
        self.entries.append((name, 2 if half else 0, a))

    def string(self, name, value):
        self.entries.append((name, 1, str(value)))

    def write(self, path):
        with open(path, "wb") as f:
            f.write(b"SPK1")
            f.write(struct.pack("<i", len(self.entries)))
            for name, kind, value in self.entries:
                nb = name.encode("utf-8")
                f.write(struct.pack("<H", len(nb)))
                f.write(nb)
                f.write(struct.pack("<B", kind))
                if kind == 1:
                    vb = value.encode("utf-8")
                    f.write(struct.pack("<i", len(vb)))
                    f.write(vb)
                else:
                    f.write(struct.pack("<B", value.ndim))
                    for d in value.shape:
                        f.write(struct.pack("<i", d))
                    f.write(value.astype("<f2" if kind == 2 else "<f4").tobytes())
