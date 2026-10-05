import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

out = Path("fixtures")
out.mkdir(exist_ok=True)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 48)
cases = {"receipt": ["LOCAL OCR", "Invoice 4729", "Total 38.50"],
         "note": ["OFFLINE NOTES", "Blue river", "Code 8264"]}
for name, lines in cases.items():
    image = Image.new("RGB", (768, 448), "white")
    draw = ImageDraw.Draw(image)
    for i, line in enumerate(lines):
        draw.text((40, 50 + i * 105), line, font=font, fill="black")
    image.save(out / f"{name}.png")
(out / "expected.json").write_text(json.dumps(cases, indent=2) + "\n")
