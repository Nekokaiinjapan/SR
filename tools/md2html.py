#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tiny dependency-free Markdown -> HTML converter for reading README files.

Handles: headings, fenced code, tables, lists, blockquotes, rules,
inline code / bold / links. Not a full CommonMark implementation, but
enough for project docs.
"""
import html
import re
import sys


def inline(text: str) -> str:
    text = html.escape(text, quote=False)
    # inline code first so its content is not touched by other rules
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"!\[([^\]]*)\]\(([^)]+)\)", r'<img alt="\1" src="\2">', text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<em>\1</em>", text)
    return text


def convert(md: str) -> str:
    lines = md.splitlines()
    out, i = [], 0
    in_code = False
    code_lang = ""
    buf = []

    def flush_para():
        if buf:
            out.append("<p>" + inline(" ".join(buf)) + "</p>")
            buf.clear()

    while i < len(lines):
        line = lines[i]

        if line.startswith("```"):
            if not in_code:
                flush_para()
                in_code = True
                code_lang = line[3:].strip()
                out.append(f'<pre><code class="lang-{code_lang}">')
            else:
                in_code = False
                out.append("</code></pre>")
            i += 1
            continue

        if in_code:
            out.append(html.escape(line))
            i += 1
            continue

        if not line.strip():
            flush_para()
            i += 1
            continue

        # raw HTML block (e.g. a centred <img>): pass through untouched
        if line.lstrip().startswith("<") and line.rstrip().endswith(">"):
            flush_para()
            out.append(line.strip())
            i += 1
            continue

        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            flush_para()
            lvl = len(m.group(1))
            out.append(f"<h{lvl}>{inline(m.group(2))}</h{lvl}>")
            i += 1
            continue

        if re.match(r"^\s*\|.*\|\s*$", line) and i + 1 < len(lines) \
                and re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i + 1]):
            flush_para()
            header = [c.strip() for c in line.strip().strip("|").split("|")]
            out.append("<table><thead><tr>")
            out.extend(f"<th>{inline(c)}</th>" for c in header)
            out.append("</tr></thead><tbody>")
            i += 2
            while i < len(lines) and re.match(r"^\s*\|.*\|\s*$", lines[i]):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                out.append("<tr>")
                out.extend(f"<td>{inline(c)}</td>" for c in cells)
                out.append("</tr>")
                i += 1
            out.append("</tbody></table>")
            continue

        if re.match(r"^\s*([-*]|\d+\.)\s+", line):
            flush_para()
            ordered = bool(re.match(r"^\s*\d+\.\s+", line))
            tag = "ol" if ordered else "ul"
            out.append(f"<{tag}>")
            while i < len(lines) and re.match(r"^\s*([-*]|\d+\.)\s+", lines[i]):
                item = re.sub(r"^\s*([-*]|\d+\.)\s+", "", lines[i])
                out.append(f"<li>{inline(item)}</li>")
                i += 1
            out.append(f"</{tag}>")
            continue

        if line.startswith(">"):
            flush_para()
            out.append("<blockquote>" + inline(line.lstrip("> ")) + "</blockquote>")
            i += 1
            continue

        if re.match(r"^\s*(-{3,}|\*{3,})\s*$", line):
            flush_para()
            out.append("<hr>")
            i += 1
            continue

        buf.append(line.strip())
        i += 1

    flush_para()
    if in_code:
        out.append("</code></pre>")
    return "\n".join(out)


CSS = """
body{max-width:900px;margin:40px auto;padding:0 24px;
 font:16px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;color:#222}
h1,h2,h3{line-height:1.3;margin-top:1.6em}
h1{border-bottom:2px solid #eee;padding-bottom:.3em}
h2{border-bottom:1px solid #eee;padding-bottom:.25em}
code{background:#f4f4f6;padding:.15em .35em;border-radius:4px;
 font-family:Consolas,"Courier New",monospace;font-size:.92em}
pre{background:#f6f8fa;padding:14px 16px;border-radius:8px;overflow:auto}
pre code{background:none;padding:0}
table{border-collapse:collapse;margin:1em 0;font-size:.95em}
th,td{border:1px solid #ddd;padding:7px 12px;text-align:left;vertical-align:top}
th{background:#f7f7f9}
tr:nth-child(even) td{background:#fcfcfd}
blockquote{border-left:4px solid #ddd;margin:1em 0;padding:.2em 1em;color:#555}
a{color:#0366d6}
hr{border:0;border-top:1px solid #eee;margin:2em 0}
"""


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: md2html.py <input.md> [output.html]")
        return 2
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else src.rsplit(".", 1)[0] + ".html"

    with open(src, "r", encoding="utf-8") as f:
        md = f.read()

    title = "Document"
    m = re.search(r"^#\s+(.*)$", md, re.M)
    if m:
        title = re.sub(r"[*`]", "", m.group(1)).strip()

    page = (
        "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
        f"<title>{html.escape(title)}</title><style>{CSS}</style></head><body>"
        + convert(md)
        + "</body></html>"
    )
    with open(dst, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"{dst}  ({len(page)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
