"""Refresh offline syntax highlighting after editing the guide or its Java examples."""
from html import unescape
from pathlib import Path
import re
import subprocess
from textwrap import dedent

HERE = Path(__file__).resolve().parent
PAGE = HERE / "movement-and-knockback.html"


def java_method(source, name):
    # Top-level example methods use four-space indentation, including the closing brace.
    pattern = rf"^    (?:public )?static [^\n(]+ {re.escape(name)}\(.*?^    \}}"
    match = re.search(pattern, source, flags=re.MULTILINE | re.DOTALL)
    if not match:
        raise ValueError(f"Missing Java method {name}")
    return dedent(match.group())


def highlight_page(page, java):
    script = page.split("</main><script>", 1)[1]
    explorer = script.split("const f32 = Math.fround;", 1)[1].split("const element =", 1)[0]
    explorer = "const f32 = Math.fround;" + explorer.rstrip()

    def highlight(block):
        attrs, language, names, body = block.groups()
        if language == "java":
            source = "\n\n".join(java_method(java, name) for name in names.split())
        elif language == "javascript":
            source = explorer
        else:
            source = unescape(re.sub(r"</?span\b[^>]*>", "", body))
        result = subprocess.run(
            ["pygmentize", "-l", language, "-f", "html", "-O", "nowrap=True"],
            input=source, capture_output=True, text=True, check=True,
        )
        markup = result.stdout.rstrip("\n")
        return f"<pre><code{attrs}>{markup}</code></pre>"

    listing = r'<pre><code( data-lang="([^"]+)"(?: data-methods="([^"]+)")?(?: id="[^"]+")?)>(.*?)</code></pre>'
    return re.sub(listing, highlight, page, flags=re.DOTALL)


if __name__ == "__main__":
    page = PAGE.read_text()
    updated = highlight_page(page, (HERE / "MovementGuide.java").read_text())
    if updated != page:
        PAGE.write_text(updated)
