"""Create a synthetic 32x64 tile and an exactly representable FP32 reference."""
import argparse
import random
import struct
from pathlib import Path


def make_packet():
    frames, reduction, columns = 32, 64, 64
    rng = random.Random(20261109)
    x = [[rng.randint(-7, 7) for _ in range(reduction)] for _ in range(frames)]
    w = [[rng.randint(-7, 7) for _ in range(reduction)] for _ in range(columns)]
    dot = [sum(a * b for a, b in zip(row, channel)) for row in x for channel in w]
    scales = [rng.choice((0.125, 0.25, 0.5, 1.0)) for _ in dot]
    prior = [rng.randint(-32, 32) * 0.25 for _ in dot]
    expected = [d * s + p for d, s, p in zip(dot, scales, prior)]
    inputs = bytes(v & 255 for matrix in (x, w) for row in matrix for v in row)
    side = bytearray()
    for start in range(0, len(dot), 512):
        for lane in (scales, prior):
            side.extend(struct.pack('<512f', *lane[start:start + 512]))
    return inputs + side + struct.pack('<2048f', *expected)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    with args.output.open('xb') as handle:
        handle.write(make_packet())
