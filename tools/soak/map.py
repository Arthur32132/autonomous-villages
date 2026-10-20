#!/usr/bin/env python3
"""Top-down picture of a soak village: start and end side by side, changed columns outlined.

    python3 tools/soak/map.py build/soak-reports/<batch>/<seed>-<difficulty> [--scale 4] [--out village.png] [--gif]
"""
import argparse
import json
import sys
import zlib
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # loading and diffing frames work without Pillow; drawing needs it
    Image = ImageDraw = None

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


def explain(folder):
    """Why a report folder has no map frames: a failed run's reason, or the runs of a batch folder."""
    if not folder.is_dir():
        return ' (no such report folder)'
    if (folder / 'soak-result.json').exists():
        try:
            r = json.loads((folder / 'soak-result.json').read_text(encoding='utf-8'))
            return f" (soak-result.json: completed={r.get('completed')}, reason={r.get('reason')})"
        except (ValueError, AttributeError):
            return ' (soak-result.json is unreadable)'
    runs = sorted(p.parent.name for p in folder.glob('*/map-start.json'))
    if runs:
        return ' (this is a batch folder; pass one run: ' + ', '.join(runs) + ')'
    return ' (no soak-result.json either: the run failed before the probe started)'


def numbers(text, name, path):
    try:
        return [int(c) for c in str(text).rstrip(',').split(',') if c.strip()]
    except ValueError:
        raise ValueError(f'{path}: "{name}" is not a comma list of integers') from None


def load(path):
    """(data, cells, heights) of a map frame; a missing, partial or inconsistent frame raises ValueError saying what is wrong."""
    path = Path(path)
    if not path.is_file():
        raise ValueError(f'{path}: no such map frame{explain(path.parent)}')
    try:
        data = json.loads(path.read_text(encoding='utf-8'))
    except ValueError as error:
        raise ValueError(f'{path}: not valid JSON ({error})') from None
    if not isinstance(data, dict):
        raise ValueError(f'{path}: not a map frame')
    absent = [k for k in ('minX', 'minZ', 'width', 'depth', 'palette', 'cells', 'heights') if k not in data]
    if absent:
        raise ValueError(f'{path}: missing field(s) {", ".join(absent)}')
    w, d, palette = data['width'], data['depth'], data['palette']
    if not (isinstance(w, int) and isinstance(d, int) and w > 0 and d > 0) or not isinstance(palette, list):
        raise ValueError(f'{path}: bad size {w}x{d} or palette')
    cells, heights = numbers(data['cells'], 'cells', path), numbers(data['heights'], 'heights', path)
    if len(cells) != w * d or len(heights) != w * d:
        raise ValueError(f'{path}: {len(cells)} cells and {len(heights)} heights for a {w}x{d} area')
    if any(not 0 <= c < len(palette) for c in cells):
        raise ValueError(f'{path}: a cell points outside the palette of {len(palette)} blocks')
    return data, cells, heights


def changed_columns(start, end):
    """Indices of end-frame columns whose top block differs from the start frame at the same world position."""
    (a, before, _), (b, after, _) = start, end
    out = set()
    for i, index in enumerate(after):
        x, z = b['minX'] + i % b['width'] - a['minX'], b['minZ'] + i // b['width'] - a['minZ']
        if 0 <= x < a['width'] and 0 <= z < a['depth'] and a['palette'][before[z * a['width'] + x]] != b['palette'][index]:
            out.add(i)
    return out


def frames(folder):
    """Saved frames in time order: start, every half day, end."""
    found = [folder / 'map-start.json'] + sorted(folder.glob('map-t*.json')) + [folder / 'map-end.json']
    return [f for f in found if f.is_file()]


def frame_label(frame):
    if frame.name in ('map-start.json', 'map-end.json'):
        return frame.name[4:-5]
    try:
        return f"day {int(frame.stem[5:]) / 24000:.1f}"
    except ValueError:
        return frame.stem


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
    for vx, vz, baby in (v for v in data.get('villagers') or [] if isinstance(v, list) and len(v) == 3):
        cx, cz = (vx - data['minX']) * scale, (vz - data['minZ']) * scale
        r = scale * (0.8 if baby else 1.3)
        draw.ellipse([cx - r, cz - r, cx + r, cz + r], fill=(30, 200, 255) if baby else (20, 60, 220), outline=(255, 255, 255))
    if isinstance(data.get('bell'), list) and len(data['bell']) >= 2:
        bx, bz = (data['bell'][0] - data['minX']) * scale, (data['bell'][1] - data['minZ']) * scale
        draw.rectangle([bx - scale, bz - scale, bx + 2 * scale, bz + 2 * scale], outline=(255, 215, 0), width=2)
    return img


def timelapse(folder, scale, out):
    """Every saved frame (start, every half day, end) as an animated GIF, changes since the start outlined."""
    found = frames(folder)
    if not found:
        raise ValueError(f'{folder}: no map frames (map-start.json, map-t*.json, map-end.json){explain(folder)}')
    start = load(found[0])
    images = []
    for frame in found:
        data, cells, heights = load(frame)
        img = render(data, cells, heights, scale, changed_columns(start, (data, cells, heights)))
        label = frame_label(frame)
        draw = ImageDraw.Draw(img)
        draw.rectangle([0, 0, 90, 16], fill=(0, 0, 0))
        draw.text((4, 2), f'{label}  villagers {len(data.get("villagers") or [])}', fill=(255, 255, 255))
        images.append(img)
    target = Path(out) if out else folder / 'village-timelapse.gif'
    images[0].save(target, save_all=True, append_images=images[1:], duration=900, loop=0)
    print(f'{target}: {len(images)} frames')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('report')
    parser.add_argument('--scale', type=int, default=4)
    parser.add_argument('--out', default=None)
    parser.add_argument('--gif', action='store_true', help='timelapse of every saved frame instead of a before/after picture')
    args = parser.parse_args(argv)
    folder = Path(args.report)
    if Image is None:
        sys.exit('map.py needs Pillow: python3 -m pip install pillow')
    try:
        if args.gif:
            timelapse(folder, args.scale, args.out)
            return
        start = load(folder / 'map-start.json')
        end = load(folder / 'map-end.json')
    except (OSError, ValueError) as error:
        sys.exit(f'map.py: {error}')
    changed = changed_columns(start, end)
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
