from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "branding" / "logo_source.jpg"
RES = ROOT / "app" / "src" / "main" / "res"
BG = (255, 250, 229, 255)


def rounded_art(size: int, inset_ratio: float, circular: bool = False) -> Image.Image:
    source = Image.open(SOURCE).convert("RGB")
    edge = min(source.size)
    left = (source.width - edge) // 2
    top = (source.height - edge) // 2
    source = source.crop((left, top, left + edge, top + edge))
    inset = round(size * inset_ratio)
    art_size = size - inset * 2
    source = source.resize((art_size, art_size), Image.Resampling.LANCZOS)
    # Keep the outer area transparent: legacy launchers get a distinct silhouette,
    # while adaptive launchers provide their own solid background layer.
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (art_size, art_size), 0)
    draw = ImageDraw.Draw(mask)
    radius = art_size // 2 if circular else round(art_size * 0.18)
    draw.rounded_rectangle((0, 0, art_size - 1, art_size - 1), radius=radius, fill=255)
    canvas.paste(source, (inset, inset), mask)
    return canvas


def save_png(image: Image.Image, directory: Path, name: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    image.save(directory / name, "PNG", optimize=True)


legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
adaptive = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

for density, size in legacy.items():
    target = RES / f"mipmap-{density}"
    save_png(rounded_art(size, 0.04), target, "ic_launcher.png")
    save_png(rounded_art(size, 0.04, circular=True), target, "ic_launcher_round.png")

for density, size in adaptive.items():
    # 12% inset keeps the face and hair within Android's 66dp adaptive-icon safe zone.
    save_png(rounded_art(size, 0.12), RES / f"drawable-{density}", "ic_launcher_foreground.png")
