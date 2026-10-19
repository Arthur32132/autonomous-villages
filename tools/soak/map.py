#!/usr/bin/env python3
"""Top-down picture of a soak village: start and end side by side, changed columns outlined.

    python3 tools/soak/map.py build/soak-reports/<batch>/<seed>-<difficulty> [--scale 4] [--out village.png] [--gif]
"""
import argparse
import json
import zlib
from pathlib import Path

from PIL import Image, ImageDraw

COLOURS = {
    'grass_block': (95, 159, 53), 'short_grass': (110, 170, 60), 'tall_grass': (110, 170, 60), 'fern': (90, 150, 60),
    'dirt': (134, 96, 67), 'coarse_dirt': (119, 85, 59), 'podzol': (91, 63, 24), 'dirt_path': (180, 150, 90),
    'farmland': (110, 75, 45), 'sand': (219, 207, 163), 'red_sand': (190, 102, 33), 'gravel': (136, 126, 126),
    'stone': (125, 125, 125), 'cobblestone': (110, 110, 110), 'mossy_cobblestone': (100, 120, 100),
    'water': (52, 90, 200), 'lava': (230, 90, 10), 'snow': (240, 245, 250), 'snow_block': (240, 245, 250),
    'powder_snow': (230, 240, 250), 'ice': (160, 190, 250), 'sandstone': (216, 203, 155), 'cut_sandstone': (216, 203, 155),
    'smooth_sandstone': (216, 203, 155), 'terracotta': (152, 94, 67), 'bell': (250, 215, 40), 'torch': (255, 220, 60),
    'wall_torch': (255, 220, 60), 'lantern': (255, 200, 80), 'chest': (160, 110, 40), 'crafting_table': (150, 100, 50),
    'furnace': (90, 90, 90), 'hay_block': (200, 170, 40), 'wheat': (200, 190, 60), 'carrots': (230, 140, 30),
    'potatoes': (200, 180, 90), 'beetroots': (160, 40, 50), 'composter': (120, 80, 40), 'water_cauldron': (60, 90, 200),
}
WOOD = {'planks': (170, 130, 80), 'log': (110, 80, 45), 'wood': (110, 80, 45), 'leaves': (50, 110, 40), 'stairs': (160, 120, 70),
        'slab': (165, 125, 75), 'fence': (140, 100, 60), 'door': (140, 95, 55), 'bed': (200, 40, 40), 'glass': (200, 230, 240),
        'wool': (230, 230, 230), 'carpet': (220, 220, 220), 'sign': (180, 140, 90), 'trapdoor': (140, 100, 60), 'roof': (120, 80, 60)}


def colour(block):
    if block in COLOURS:
        return COLOURS[block]
    for suffix, rgb in WOOD.items():
        if block.endswith(suffix) or suffix in block:
            return rgb
    h = zlib.crc32(block.encode())
    return (80 + h % 120, 80 + (h >> 8) % 120, 80 + (h >> 16) % 120)


def load(path):
    data = json.loads(Path(path).read_text())
    cells = [int(c) for c in data['cells'].rstrip(',').split(',')]
    heights = [int(c) for c in data['heights'].rstrip(',').split(',')]
    return data, cells, heights


def render(data, cells, heights, scale, changed=None):
    w, d, palette = data['width'], data['depth'], data['palette']
    low, high = min(heights), max(heights)
    img = Image.new('RGB', (w * scale, d * scale))
    draw = ImageDraw.Draw(img)
    for i, index in enumerate(cells):
        x, z = i % w, i // w
        block = palette[index]
        shade = 0.75 + 0.25 * (heights[i] - low) / max(1, high - low)
        r, g, b = (int(c * shade) for c in colour(block))
        draw.rectangle([x * scale, z * scale, x * scale + scale - 1, z * scale + scale - 1], fill=(r, g, b))
        if changed and i in changed:
            draw.rectangle([x * scale, z * scale, x * scale + scale - 1, z * scale + scale - 1], outline=(255, 0, 255))
        if 'torch' in block or block == 'lantern':
            draw.ellipse([x * scale - 1, z * scale - 1, x * scale + scale, z * scale + scale], fill=(255, 230, 50))
    for vx, vz, baby in data.get('villagers', []):
        cx, cz = (vx - data['minX']) * scale, (vz - data['minZ']) * scale
        r = scale * (0.8 if baby else 1.3)
        draw.ellipse([cx - r, cz - r, cx + r, cz + r], fill=(30, 200, 255) if baby else (20, 60, 220), outline=(255, 255, 255))
    if 'bell' in data:
        bx, bz = (data['bell'][0] - data['minX']) * scale, (data['bell'][1] - data['minZ']) * scale
        draw.rectangle([bx - scale, bz - scale, bx + 2 * scale, bz + 2 * scale], outline=(255, 215, 0), width=2)
    return img


def timelapse(folder, scale, out):
    """Every saved frame (start, every half day, end) as an animated GIF, changes since the start outlined."""
    frames = [folder / 'map-start.json'] + sorted(folder.glob('map-t*.json')) + [folder / 'map-end.json']
    frames = [f for f in frames if f.exists()]
    start = load(frames[0])
    images = []
    for frame in frames:
        data, cells, heights = load(frame)
        changed = {i for i, (a, b) in enumerate(zip(start[1], cells)) if start[0]['palette'][a] != data['palette'][b]}
        img = render(data, cells, heights, scale, changed)
        label = 'start' if frame.name == 'map-start.json' else 'end' if frame.name == 'map-end.json' else \
            f"day {int(frame.stem[5:]) / 24000:.1f}"
        draw = ImageDraw.Draw(img)
        draw.rectangle([0, 0, 90, 16], fill=(0, 0, 0))
        draw.text((4, 2), f'{label}  villagers {len(data.get("villagers", []))}', fill=(255, 255, 255))
        images.append(img)
    target = Path(out) if out else folder / 'village-timelapse.gif'
    images[0].save(target, save_all=True, append_images=images[1:], duration=900, loop=0)
    print(f'{target}: {len(images)} frames')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('report')
    parser.add_argument('--scale', type=int, default=4)
    parser.add_argument('--out', default=None)
    parser.add_argument('--gif', action='store_true', help='timelapse of every saved frame instead of a before/after picture')
    args = parser.parse_args()
    folder = Path(args.report)
    if args.gif:
        timelapse(folder, args.scale, args.out)
        return
    start = load(folder / 'map-start.json')
    end = load(folder / 'map-end.json')
    changed = {i for i, (a, b) in enumerate(zip(start[1], end[1])) if start[0]['palette'][a] != end[0]['palette'][b]}
    left = render(*start, args.scale)
    right = render(*end, args.scale, changed)
    gap = 8
    out = Image.new('RGB', (left.width + right.width + gap, max(left.height, right.height)), (255, 255, 255))
    out.paste(left, (0, 0))
    out.paste(right, (left.width + gap, 0))
    target = Path(args.out) if args.out else folder / 'village-map.png'
    out.save(target)
    print(f'{target}: {len(changed)} columns changed')


if __name__ == '__main__':
    main()
