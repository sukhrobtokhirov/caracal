#!/usr/bin/env python3
"""Render every icon the build ships from branding/caracal-source.png.

    python3 branding/render-icons.py

The source is the artwork as it was handed over, unmodified and unkeyed: black
and grey line art on an opaque white field, 740x740. Everything downstream —
the transparency, the crop, the tile, the five icon sizes — is derived here, so
that replacing the source file and re-running is the whole of changing the mark.

`iconutil` builds the .icns and ships with macOS; Pillow is the only install
(`pip install pillow`). The outputs are committed, so this runs when the artwork
changes and at no other time.
"""

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "branding" / "caracal-source.png"
HERO = ROOT / "branding" / "caracal.png"
ICONS = ROOT / "app" / "icons"
WINDOW_ICON = ROOT / "app" / "src" / "main" / "resources" / "caracal.png"

TILE = (227, 166, 73)   # the theme's tertiary amber, #E3A649
CANVAS = 1024
CORNER = 0.2237         # the ratio Apple's squircle approximates; used on all three platforms
MAC_ART = 0.8046        # macOS art fills 824 of 1024; the margin is where the shadow goes
FILL = 0.92             # the head's height as a fraction of the tile
TOP = 0.10              # where its ears start, leaving the chin to run off the bottom
HEAD = 0.66             # the crop: ears to chin, without the chest below them
ICNS_SIZES = [16, 32, 128, 256, 512]
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]


def keyed() -> Image.Image:
    """The artwork with its white field removed and its white markings kept.

    Which white is background cannot be decided by colour — the face is full of
    white — so the field is found by flooding inward from the corners. The ring
    where the drawing was antialiased against white is then given partial alpha
    from its own lightness, because a hard cut there leaves a pale fringe that
    is invisible on white and obvious on the amber tile.
    """
    art = Image.open(SOURCE).convert("RGB")
    width, height = art.size

    flooded = art.copy()
    for corner in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)):
        ImageDraw.floodfill(flooded, corner, (255, 0, 255), thresh=30)
    field = flooded.point(lambda _: 0).convert("L")
    field.putdata([255 if p == (255, 0, 255) else 0 for p in flooded.getdata()])

    fringe = ImageChops.subtract(field.filter(ImageFilter.MaxFilter(5)), field)
    lightness = art.convert("L")

    alpha = Image.new("L", art.size, 255)
    alpha.paste(0, (0, 0), field)
    alpha.paste(lightness.point(lambda p: 255 - p), (0, 0), fringe)

    out = art.convert("RGBA")
    out.putalpha(alpha)
    return out.crop(out.getbbox())


def tile() -> Image.Image:
    """The rounded amber tile, its corners cut to transparency, antialiased 4x."""
    mask = Image.new("L", (CANVAS * 4,) * 2, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, CANVAS * 4 - 1, CANVAS * 4 - 1), radius=int(CANVAS * 4 * CORNER), fill=255,
    )
    out = Image.new("RGBA", (CANVAS,) * 2, TILE + (255,))
    out.putalpha(mask.resize((CANVAS,) * 2, Image.LANCZOS))
    return out


def icon() -> Image.Image:
    """The head on the tile.

    The chest and the diagonal cut it ends on are left out: they cost the ears
    the room that is the only thing still legible at 32px, and that cut was
    drawn for a sticker, not for a square. What remains is hung from the top and
    allowed to run off the bottom, where the tile's own edge crops it — a flat
    cut stopping just short of the edge reads as a mistake, and one that reaches
    it reads as a crop.
    """
    art = keyed()
    head = art.crop((0, 0, art.width, int(art.height * HEAD)))
    head = head.crop(head.getbbox())

    side = int(CANVAS * FILL)
    scaled = head.resize((round(head.width * side / head.height), side), Image.LANCZOS)

    layer = Image.new("RGBA", (CANVAS,) * 2, (0, 0, 0, 0))
    layer.paste(scaled, ((CANVAS - scaled.width) // 2, int(CANVAS * TOP)), scaled)

    out = tile()
    out.alpha_composite(layer)
    # Whatever ran past the tile is cut by the tile, corners included.
    out.putalpha(Image.composite(out.getchannel("A"), Image.new("L", out.size, 0), tile().getchannel("A")))
    return out


def thin(image: Image.Image) -> Image.Image:
    """Three inks and a flat tile do not need a truecolour PNG. The README image
    and the icon inside the jar are the two that are paid for on every open."""
    return image.quantize(colors=128, method=Image.FASTOCTREE)


def main() -> None:
    ICONS.mkdir(parents=True, exist_ok=True)
    WINDOW_ICON.parent.mkdir(parents=True, exist_ok=True)

    full = icon()

    # Windows and Linux take the tile as it is; macOS insets it so the system's
    # drop shadow has somewhere to fall.
    art = full.resize((int(CANVAS * MAC_ART),) * 2, Image.LANCZOS)
    mac = Image.new("RGBA", (CANVAS,) * 2, (0, 0, 0, 0))
    mac.paste(art, ((CANVAS - art.width) // 2,) * 2, art)

    small = full.resize((512, 512), Image.LANCZOS)
    thin(small).save(HERO, optimize=True)
    thin(small).save(ICONS / "caracal.png", optimize=True)
    thin(small).save(WINDOW_ICON, optimize=True)
    full.resize((256, 256), Image.LANCZOS).save(
        ICONS / "caracal.ico", sizes=[(s, s) for s in ICO_SIZES],
    )

    with tempfile.TemporaryDirectory() as work:
        iconset = Path(work) / "caracal.iconset"
        iconset.mkdir()
        for size in ICNS_SIZES:
            mac.resize((size,) * 2, Image.LANCZOS).save(iconset / f"icon_{size}x{size}.png")
            mac.resize((size * 2,) * 2, Image.LANCZOS).save(iconset / f"icon_{size}x{size}@2x.png")
        subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(ICONS / "caracal.icns")],
            check=True,
        )

    for path in [HERO, *sorted(ICONS.iterdir()), WINDOW_ICON]:
        print(f"{path.relative_to(ROOT)}  {path.stat().st_size // 1024} KiB")


if __name__ == "__main__":
    if shutil.which("iconutil") is None:
        sys.exit("iconutil not found: this script builds the .icns on macOS")
    main()
