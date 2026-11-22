"""Optional in-process Rust geometry for the existing arena and observation protocol."""
import ctypes
import os
from pathlib import Path
import subprocess
import sys

import numpy as np

from arena import ARROWS, PROJ, TERRAIN, Arena

_P = ctypes.c_void_p
_N = ctypes.c_size_t


def build(directory):
    directory.mkdir(parents=True, exist_ok=True)
    library = directory / ('libbow_geometry.dylib' if sys.platform == 'darwin' else 'libbow_geometry.so')
    subprocess.run(['rustc', '--edition=2021', '--crate-type=cdylib', '-C', 'opt-level=3',
                    '-C', 'target-cpu=native', '-C', 'panic=abort', '-o', str(library),
                    str(Path(__file__).with_name('geometry.rs'))], check=True)
    return library.resolve()


class Geometry:
    def __init__(self, path):
        lib = ctypes.CDLL(str(path))
        self.terrain = lib.bow_terrain
        self.terrain.argtypes = [_N, _N, _P, _P, _P, _N, _P, _P]
        self.terrain.restype = None
        self.view = lib.bow_view
        self.view.argtypes = [_N] + [_P] * 10
        self.view.restype = None
        self.move = lib.bow_move
        self.move.argtypes = [_N] + [_P] * 7
        self.move.restype = None


def ptr(array):
    assert array.flags.c_contiguous
    return array.ctypes.data


class RustArena(Arena):
    def __init__(self, *args, geometry, **kwargs):
        super().__init__(*args, **kwargs)
        self.geometry = geometry

    def terrain_hit(self, origin, delta, rows=None):
        boxes, active = (self.boxes, self.active_box) if rows is None else (self.boxes[rows], self.active_box[rows])
        count = len(boxes)
        delta = np.ascontiguousarray(delta, dtype=np.float64)
        origin = np.ascontiguousarray(origin, dtype=np.float64)
        output = np.empty(delta.shape[:-1], dtype=np.float64)
        rays = delta.size // (count * 3)
        stride = origin.size // (count * 3)
        if stride not in (1, rays):
            origin = np.ascontiguousarray(np.broadcast_to(origin, delta.shape))
            stride = rays
        self.geometry.terrain(count, rays, ptr(boxes), ptr(active), ptr(origin), stride, ptr(delta), ptr(output))
        return output

    def view_features(self, seat, p, yaw):
        output = np.empty((self.n, TERRAIN + ARROWS * PROJ), dtype=np.float32)
        seats = np.ascontiguousarray(np.broadcast_to(seat, self.n), dtype=np.int32)
        self.geometry.view(self.n, ptr(p), ptr(yaw), ptr(seats), ptr(self.boxes), ptr(self.active_box),
                           ptr(self.ap), ptr(self.av), ptr(self.age), ptr(self.apearl), ptr(output))
        return output

    def move_positions(self, go, drag):
        self.geometry.move(self.n, ptr(self.pos), ptr(self.vel), ptr(self.ground),
                           ptr(self.boxes), ptr(self.active_box), ptr(go), ptr(drag))


def arena_factory():
    path = os.environ.get('BOW_GEOMETRY_LIB')
    if path is None:
        return Arena
    geometry = Geometry(path)
    return lambda *args, **kwargs: RustArena(*args, geometry=geometry, **kwargs)


if __name__ == '__main__':
    print(build(Path(sys.argv[1])))
