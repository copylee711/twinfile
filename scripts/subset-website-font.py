"""Build a local CJK webfont from the official Noto Sans SC variable TTF.

Usage: python scripts/subset-website-font.py /path/to/NotoSansSC.ttf
Requires fonttools and brotli. Regenerate after changing the website's Chinese copy.
The upstream font is distributed under website/assets/NotoSansSC-LICENSE.txt.
"""
from html.parser import HTMLParser
from pathlib import Path
import sys
from fontTools import subset


class Text(HTMLParser):
    def handle_data(self, data):
        self.characters.update(data)


root = Path(__file__).resolve().parents[1] / "website"
parser = Text()
parser.characters = set()
for page in root.glob("*.html"):
    parser.feed(page.read_text(encoding="utf-8"))
options = subset.Options()
options.flavor = "woff2"
font = subset.load_font(sys.argv[1], options)
worker = subset.Subsetter(options=options)
worker.populate(text="".join(sorted(parser.characters)))
worker.subset(font)
out = root / "assets" / "noto-sans-sc.woff2"
subset.save_font(font, str(out), options)
print(f"{len(parser.characters)} characters, {out.stat().st_size:,} bytes")
