"""Generate continuous dual-ring radial menu base + per-sector hover wedges."""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "maxfastbuild-fabric/src/main/resources/assets/maxfastbuild/textures/gui"
OUT.mkdir(parents=True, exist_ok=True)

SIZE = 512
CX = CY = SIZE // 2

# Pixel radii matching RadialBuildScreen hit tests (scale 512/320 of previous 160 hub)
# Previous logical outer radius ~154 on ~320 hub; keep proportions on 512 canvas.
SCALE = SIZE / 320.0
INNER_HOLE = 42 * SCALE
INNER_OUTER = 91 * SCALE
OUTER_INNER = 96 * SCALE
OUTER_OUTER = 154 * SCALE
HUB = 36 * SCALE

OUTER_COUNT = 8
INNER_COUNT = 5

BG = (14, 20, 28, 230)
OUTER_FILL = (27, 37, 49, 220)
INNER_FILL = (42, 52, 66, 210)
EDGE = (75, 92, 112, 255)
DIVIDER = (85, 101, 121, 255)
HOVER_OUTER = (101, 224, 138, 200)
HOVER_INNER = (92, 203, 245, 200)
HUB_FILL = (14, 20, 28, 235)
HUB_EDGE = (100, 116, 139, 220)
TITLE_RING = (142, 233, 255, 40)


def polar(cx: float, cy: float, r: float, a: float) -> tuple[float, float]:
    return cx + math.cos(a) * r, cy + math.sin(a) * r


def sector_poly(
    cx: float,
    cy: float,
    r0: float,
    r1: float,
    a0: float,
    a1: float,
    steps: int = 24,
) -> list[tuple[float, float]]:
    pts: list[tuple[float, float]] = []
    for i in range(steps + 1):
        t = i / steps
        a = a0 + (a1 - a0) * t
        pts.append(polar(cx, cy, r1, a))
    for i in range(steps + 1):
        t = i / steps
        a = a1 - (a1 - a0) * t
        pts.append(polar(cx, cy, r0, a))
    return pts


def draw_ring(
    draw: ImageDraw.ImageDraw,
    count: int,
    r0: float,
    r1: float,
    fill: tuple[int, int, int, int],
    gap: float = 0.012,
) -> None:
    sector = math.tau / count
    for i in range(count):
        a0 = -math.pi / 2 + i * sector + gap
        a1 = a0 + sector - 2 * gap
        poly = sector_poly(CX, CY, r0, r1, a0, a1, steps=28)
        draw.polygon(poly, fill=fill)
        # divider at sector start
        x0, y0 = polar(CX, CY, r0, -math.pi / 2 + i * sector)
        x1, y1 = polar(CX, CY, r1, -math.pi / 2 + i * sector)
        draw.line([(x0, y0), (x1, y1)], fill=DIVIDER, width=2)
    # inner/outer arcs approximated by many short chords
    for ring_r, width in ((r0, 2), (r1, 2)):
        pts = [polar(CX, CY, ring_r, -math.pi / 2 + i * math.tau / 96) for i in range(97)]
        draw.line(pts, fill=EDGE, width=width)


def make_base() -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    # soft backdrop disc
    d.ellipse(
        (CX - OUTER_OUTER - 8, CY - OUTER_OUTER - 8, CX + OUTER_OUTER + 8, CY + OUTER_OUTER + 8),
        fill=(0, 0, 0, 120),
    )
    draw_ring(d, OUTER_COUNT, OUTER_INNER, OUTER_OUTER, OUTER_FILL)
    draw_ring(d, INNER_COUNT, INNER_HOLE, INNER_OUTER, INNER_FILL)
    # hub
    d.ellipse((CX - HUB, CY - HUB, CX + HUB, CY + HUB), fill=HUB_FILL, outline=HUB_EDGE, width=2)
    # subtle outer glow ring
    d.ellipse(
        (CX - OUTER_OUTER - 2, CY - OUTER_OUTER - 2, CX + OUTER_OUTER + 2, CY + OUTER_OUTER + 2),
        outline=TITLE_RING,
        width=1,
    )
    return img


def make_hover_wedge(
    count: int,
    index: int,
    r0: float,
    r1: float,
    color: tuple[int, int, int, int],
) -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    sector = math.tau / count
    gap = 0.018
    a0 = -math.pi / 2 + index * sector + gap
    a1 = a0 + sector - 2 * gap
    poly = sector_poly(CX, CY, r0, r1, a0, a1, steps=32)
    d.polygon(poly, fill=color)
    # bright edge
    edge = (min(255, color[0] + 40), min(255, color[1] + 40), min(255, color[2] + 40), 255)
    d.line(poly + [poly[0]], fill=edge, width=2)
    return img


def main() -> None:
    base = make_base()
    base_path = OUT / "radial_base.png"
    base.save(base_path)
    print("wrote", base_path)

    hover_dir = OUT / "radial_hover"
    hover_dir.mkdir(parents=True, exist_ok=True)
    for i in range(OUTER_COUNT):
        p = hover_dir / f"outer_{i}.png"
        make_hover_wedge(OUTER_COUNT, i, OUTER_INNER, OUTER_OUTER, HOVER_OUTER).save(p)
        print("wrote", p)
    for i in range(INNER_COUNT):
        p = hover_dir / f"inner_{i}.png"
        make_hover_wedge(INNER_COUNT, i, INNER_HOLE, INNER_OUTER, HOVER_INNER).save(p)
        print("wrote", p)

    # Also write a small meta text for screen code radii (logical units, 320 hub)
    meta = OUT / "radial_meta.txt"
    meta.write_text(
        "\n".join(
            [
                f"texture_size={SIZE}",
                f"logical_hub=160",
                f"scale={SCALE}",
                f"inner_hole={INNER_HOLE / SCALE}",
                f"inner_outer={INNER_OUTER / SCALE}",
                f"outer_inner={OUTER_INNER / SCALE}",
                f"outer_outer={OUTER_OUTER / SCALE}",
                f"hub={HUB / SCALE}",
            ]
        ),
        encoding="utf-8",
    )
    print("wrote", meta)


if __name__ == "__main__":
    main()
