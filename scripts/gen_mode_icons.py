from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "maxfastbuild-fabric/src/main/resources/assets/maxfastbuild/textures/gui/modes"
DOCS = ROOT / "docs/assets/modes"
DOCS_PNG = DOCS / "png"
OUT.mkdir(parents=True, exist_ok=True)
DOCS.mkdir(parents=True, exist_ok=True)
DOCS_PNG.mkdir(parents=True, exist_ok=True)

DOCS_ICON_SIZE = 64

INK = (158, 203, 255, 255)
BG = (14, 20, 28, 255)


def base():
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, 31, 31), radius=4, fill=BG)
    return img, d


def make():
    modes = {}

    img, d = base()
    d.rectangle((13, 13, 18, 18), fill=INK)
    modes["single"] = img

    img, d = base()
    d.line((6, 16, 26, 16), fill=INK, width=3)
    modes["line"] = img

    img, d = base()
    d.rectangle((8, 6, 24, 26), outline=INK, width=2)
    d.line((8, 10, 24, 10), fill=INK, width=2)
    modes["wall"] = img

    img, d = base()
    d.rectangle((6, 12, 26, 20), outline=INK, width=2)
    d.line((6, 16, 26, 16), fill=INK, width=1)
    modes["floor"] = img

    img, d = base()
    d.rectangle((8, 10, 22, 24), outline=INK, width=2)
    d.line((8, 10, 14, 6), fill=INK, width=2)
    d.line((14, 6, 28, 6), fill=INK, width=2)
    d.line((22, 10, 28, 6), fill=INK, width=2)
    d.line((22, 24, 28, 20), fill=INK, width=2)
    d.line((28, 6, 28, 20), fill=INK, width=2)
    modes["cube"] = img

    img, d = base()
    d.arc((5, 5, 27, 27), 205, 335, fill=INK, width=3)
    d.ellipse((4, 20, 8, 24), fill=INK)
    d.ellipse((14, 5, 18, 9), fill=INK)
    d.ellipse((24, 20, 28, 24), fill=INK)
    modes["arc"] = img

    img, d = base()
    for x in (8, 16, 24):
        for y in (8, 16, 24):
            d.rectangle((x - 2, y - 2, x + 2, y + 2), fill=INK)
    modes["array"] = img

    img, d = base()
    d.line((6, 24, 26, 8), fill=INK, width=3)
    d.line((6, 24, 26, 24), fill=INK, width=2)
    modes["slope_floor"] = img

    img, d = base()
    d.ellipse((6, 6, 26, 26), outline=INK, width=2)
    modes["circle"] = img

    img, d = base()
    d.ellipse((8, 5, 24, 13), outline=INK, width=2)
    d.line((8, 9, 8, 23), fill=INK, width=2)
    d.line((24, 9, 24, 23), fill=INK, width=2)
    d.ellipse((8, 19, 24, 27), outline=INK, width=2)
    modes["cylinder"] = img

    img, d = base()
    d.ellipse((5, 5, 27, 27), outline=INK, width=2)
    d.ellipse((5, 12, 27, 20), outline=INK, width=1)
    modes["sphere"] = img

    img, d = base()
    d.polygon([(16, 6), (6, 24), (26, 24)], outline=INK)
    d.line((16, 6, 16, 24), fill=INK, width=1)
    modes["pyramid"] = img

    img, d = base()
    d.line((16, 5, 7, 22), fill=INK, width=2)
    d.line((16, 5, 25, 22), fill=INK, width=2)
    d.ellipse((7, 18, 25, 26), outline=INK, width=2)
    modes["cone"] = img

    svgs = {
        "single": '<circle cx="16" cy="16" r="4" fill="#9ecbff"/>',
        "line": '<line x1="6" y1="16" x2="26" y2="16" stroke="#9ecbff" stroke-width="3"/>',
        "wall": '<rect x="8" y="6" width="16" height="20" fill="none" stroke="#9ecbff" stroke-width="2"/><line x1="8" y1="10" x2="24" y2="10" stroke="#9ecbff" stroke-width="2"/>',
        "floor": '<rect x="6" y="12" width="20" height="8" fill="none" stroke="#9ecbff" stroke-width="2"/>',
        "cube": '<path d="M8 12 L14 7 L28 7 L22 12 Z M8 12 V24 L22 24 V12 M22 12 L28 7 V19 L22 24" fill="none" stroke="#9ecbff" stroke-width="2"/>',
        "arc": '<path d="M5 22 A11 11 0 0 1 27 22" fill="none" stroke="#9ecbff" stroke-width="3"/><circle cx="6" cy="22" r="2" fill="#9ecbff"/><circle cx="16" cy="7" r="2" fill="#9ecbff"/><circle cx="26" cy="22" r="2" fill="#9ecbff"/>',
        "array": '<g fill="#9ecbff"><circle cx="8" cy="8" r="2.5"/><circle cx="16" cy="8" r="2.5"/><circle cx="24" cy="8" r="2.5"/><circle cx="8" cy="16" r="2.5"/><circle cx="16" cy="16" r="2.5"/><circle cx="24" cy="16" r="2.5"/><circle cx="8" cy="24" r="2.5"/><circle cx="16" cy="24" r="2.5"/><circle cx="24" cy="24" r="2.5"/></g>',
        "slope_floor": '<polyline points="6,24 26,8 26,24 6,24" fill="none" stroke="#9ecbff" stroke-width="2"/>',
        "circle": '<circle cx="16" cy="16" r="10" fill="none" stroke="#9ecbff" stroke-width="2"/>',
        "cylinder": '<ellipse cx="16" cy="9" rx="8" ry="4" fill="none" stroke="#9ecbff" stroke-width="2"/><line x1="8" y1="9" x2="8" y2="23" stroke="#9ecbff" stroke-width="2"/><line x1="24" y1="9" x2="24" y2="23" stroke="#9ecbff" stroke-width="2"/><ellipse cx="16" cy="23" rx="8" ry="4" fill="none" stroke="#9ecbff" stroke-width="2"/>',
        "sphere": '<circle cx="16" cy="16" r="11" fill="none" stroke="#9ecbff" stroke-width="2"/><ellipse cx="16" cy="16" rx="11" ry="4" fill="none" stroke="#9ecbff" stroke-width="1"/>',
        "pyramid": '<polygon points="16,6 6,24 26,24" fill="none" stroke="#9ecbff" stroke-width="2"/>',
        "cone": '<line x1="16" y1="5" x2="7" y2="22" stroke="#9ecbff" stroke-width="2"/><line x1="16" y1="5" x2="25" y2="22" stroke="#9ecbff" stroke-width="2"/><ellipse cx="16" cy="22" rx="9" ry="4" fill="none" stroke="#9ecbff" stroke-width="2"/>',
    }

    for name, img in modes.items():
        img.save(OUT / f"{name}.png")
        img.resize((DOCS_ICON_SIZE, DOCS_ICON_SIZE), Image.Resampling.LANCZOS).save(
            DOCS_PNG / f"{name}.png"
        )
        svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">\n'
            '  <rect width="32" height="32" rx="4" fill="#0e141c"/>\n'
            f"  {svgs[name]}\n"
            "</svg>\n"
        )
        (DOCS / f"{name}.svg").write_text(svg, encoding="utf-8")

    print(f"wrote {len(modes)} png to {OUT}")
    print(f"wrote {len(modes)} svg to {DOCS}")


if __name__ == "__main__":
    make()
