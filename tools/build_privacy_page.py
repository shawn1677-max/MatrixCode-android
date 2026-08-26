#!/usr/bin/env python3
"""
Renders PRIVACY.md into docs/index.html, the page GitHub Pages serves.

PRIVACY.md stays the single source of truth: edit it, re-run this, commit both.
Generating the page rather than hand-maintaining a second copy is the point —
a privacy policy that disagrees with itself across two files is worse than none.

Usage: python3 tools/build_privacy_page.py
"""
import html
import re
import sys

SRC = "PRIVACY.md"
DST = "docs/index.html"

TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="Privacy policy for Codefall, a falling-code screensaver for Android. No data collected.">
<meta name="color-scheme" content="dark">
<style>
  :root {{
    --bg: #04070500;
    --ink: #d7e4da;
    --ink-dim: #8fa595;
    --green: #00ff41;
    --green-dim: #0b8f2e;
    --rule: #14301d;
  }}
  * {{ box-sizing: border-box; }}
  html {{ background: #040705; }}
  body {{
    margin: 0;
    background: #040705;
    color: var(--ink);
    font: 16px/1.7 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
          Helvetica, Arial, sans-serif;
    -webkit-text-size-adjust: 100%;
  }}
  .wrap {{ max-width: 46rem; margin: 0 auto; padding: 3rem 1.25rem 5rem; }}
  .brand {{
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.78rem;
    letter-spacing: 0.32em;
    text-transform: uppercase;
    color: var(--green);
    margin: 0 0 2.5rem;
  }}
  h1 {{
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 1.65rem;
    line-height: 1.25;
    color: #fff;
    margin: 0 0 1.5rem;
  }}
  h2 {{
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 1rem;
    letter-spacing: 0.06em;
    color: var(--green);
    margin: 2.75rem 0 0.75rem;
    padding-bottom: 0.5rem;
    border-bottom: 1px solid var(--rule);
  }}
  p {{ margin: 0 0 1.15rem; }}
  ul {{ margin: 0 0 1.15rem; padding-left: 1.1rem; }}
  li {{ margin-bottom: 0.55rem; }}
  li::marker {{ color: var(--green-dim); }}
  strong {{ color: #fff; font-weight: 600; }}
  code {{
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.88em;
    color: var(--green);
    background: #0c1710;
    border: 1px solid var(--rule);
    border-radius: 4px;
    padding: 0.08em 0.35em;
  }}
  a {{ color: var(--green); }}
  a:hover {{ color: #fff; }}
  footer {{
    margin-top: 3.5rem;
    padding-top: 1.25rem;
    border-top: 1px solid var(--rule);
    font-size: 0.85rem;
    color: var(--ink-dim);
  }}
</style>
</head>
<body>
<div class="wrap">
<p class="brand">Codefall</p>
{body}
<footer>
  Source and app: <a href="https://github.com/shawn1677-max/Codefall">github.com/shawn1677-max/Codefall</a>
</footer>
</div>
</body>
</html>
"""


def inline(text):
    """Markdown inline subset: escaping first so the tags we add survive."""
    text = html.escape(text, quote=False)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"(?<!mailto:)([\w.+-]+@[\w-]+\.[\w.]+)",
                  r'<a href="mailto:\1">\1</a>', text)
    return text


def parse(md):
    """Blocks only: headings, bullet lists and paragraphs, with wrapped lines joined."""
    lines = md.split("\n")
    blocks = []
    i = 0
    while i < len(lines):
        if not lines[i].strip():
            i += 1
            continue
        if lines[i].startswith("## "):
            blocks.append(("h2", lines[i][3:].strip()))
            i += 1
        elif lines[i].startswith("# "):
            blocks.append(("h1", lines[i][2:].strip()))
            i += 1
        elif lines[i].startswith("- "):
            items = []
            while i < len(lines) and lines[i].strip():
                if lines[i].startswith("- "):
                    items.append(lines[i][2:].strip())
                elif items:
                    items[-1] += " " + lines[i].strip()
                else:
                    break
                i += 1
            blocks.append(("ul", items))
        else:
            para = []
            while i < len(lines) and lines[i].strip() \
                    and not lines[i].startswith(("#", "- ")):
                para.append(lines[i].strip())
                i += 1
            blocks.append(("p", " ".join(para)))
    return blocks


def render(blocks):
    out, title = [], "Privacy Policy"
    for kind, val in blocks:
        if kind == "h1":
            title = val
            out.append(f"<h1>{inline(val)}</h1>")
        elif kind == "h2":
            out.append(f"<h2>{inline(val)}</h2>")
        elif kind == "ul":
            items = "\n".join(f"  <li>{inline(x)}</li>" for x in val)
            out.append(f"<ul>\n{items}\n</ul>")
        else:
            out.append(f"<p>{inline(val)}</p>")
    return title, "\n".join(out)


def main():
    try:
        md = open(SRC, encoding="utf-8").read()
    except FileNotFoundError:
        sys.exit(f"{SRC} not found - run this from the repository root")
    title, body = render(parse(md))
    open(DST, "w", encoding="utf-8").write(TEMPLATE.format(title=title, body=body))
    print(f"wrote {DST}  (title: {title})")


if __name__ == "__main__":
    main()
