# -*- coding: utf-8 -*-
"""Convert OryxOS course PDFs (16-20) to markdown + extract diagram images.

Usage: python _pdf2md.py
"""
import glob
import os
import re

import pymupdf as fitz  # noqa: N814

DOC_DIR = os.path.dirname(os.path.abspath(__file__))

TERMINAL = set("。！？；：")
CODE_TERM = set(";{}):")


def is_ascii_word(ch: str) -> bool:
    return bool(ch) and ch.isascii() and (ch.isalnum() or ch in "_.")


# ---------------------------------------------------------------------------
# Diagram detection
# ---------------------------------------------------------------------------
def _cluster_rects(rects, gap=18.0):
    parents = list(range(len(rects)))

    def expand(r):
        r2 = fitz.Rect(r)
        r2.x0 -= gap; r2.y0 -= gap; r2.x1 += gap; r2.y1 += gap
        return r2

    boxes = [expand(r) for r in rects]

    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parents[rb] = ra

    for i in range(len(boxes)):
        for j in range(i + 1, len(boxes)):
            if boxes[i].intersects(boxes[j]):
                union(i, j)

    groups = {}
    for i in range(len(rects)):
        groups.setdefault(find(i), []).append(fitz.Rect(rects[i]))
    return list(groups.values())


def detect_diagram_regions(page):
    """Return diagram regions (fs boxes, linked via arrows) to render."""
    drawings = page.get_drawings()
    boxes = []        # structural boxes: fill+stroke rectangles
    connectors = []   # arrows: thin stroke lines + small filled arrowheads
    backgrounds = []  # large filled rects (diagram container background)
    for d in drawings:
        r = d["rect"]
        t = d["type"]
        w, h = r.width, r.height
        if t == "fs" and w >= 25 and h >= 10:
            boxes.append(r)
        elif t == "s" and (w <= 3 or h <= 3):
            connectors.append(r)
        elif t == "f" and 5 <= w <= 9 and 5 <= h <= 9:
            connectors.append(r)  # arrowhead square
        elif t == "f" and w > 100 and h > 40:
            backgrounds.append(r)
        # everything else (text highlights, dividers) is ignored

    if not boxes:
        return []

    # cluster boxes + connectors together so arrows link boxes into one diagram
    elements = [(r, True) for r in boxes] + [(r, False) for r in connectors]
    parents = list(range(len(elements)))

    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parents[rb] = ra

    gap = 30.0
    grown = [fitz.Rect(r.x0 - gap, r.y0 - gap, r.x1 + gap, r.y1 + gap)
             for r, _ in elements]
    for i in range(len(elements)):
        for j in range(i + 1, len(elements)):
            if grown[i].intersects(grown[j]):
                union(i, j)

    groups = {}
    for i, (r, is_box) in enumerate(elements):
        groups.setdefault(find(i), []).append((r, is_box))

    regions = []
    for members in groups.values():
        n_boxes = sum(1 for _, b in members if b)
        n_conn = sum(1 for _, b in members if not b)
        if n_boxes < 2 and n_conn == 0:
            continue  # lone callout box -> keep as text
        cb = fitz.Rect()
        for r, _ in members:
            cb |= r
        grown_b = fitz.Rect(cb.x0 - 20, cb.y0 - 20, cb.x1 + 20, cb.y1 + 20)
        for bg in backgrounds:
            if bg.intersects(grown_b):
                cb |= bg
        if cb.width > 540 and cb.height > 650:
            continue
        regions.append(fitz.Rect(cb))
    return regions


# ---------------------------------------------------------------------------
# Line extraction
# ---------------------------------------------------------------------------
def classify(size):
    if size >= 18.0:
        return "h1"
    if size >= 15.0:
        return "h2"
    if size >= 11.0:
        return "body"
    if size >= 10.0:
        return "code"
    return "small"


def get_all_lines(page):
    """Return list of line dicts in reading order (no exclusion)."""
    lines = []
    d = page.get_text("dict")
    for b in d["blocks"]:
        if b["type"] != 0:
            continue
        for l in b["lines"]:
            spans = l["spans"]
            if not spans:
                continue
            text = "".join(s["text"] for s in spans)
            if not text.strip():
                continue
            x0 = min(s["bbox"][0] for s in spans)
            y0 = min(s["bbox"][1] for s in spans)
            x1 = max(s["bbox"][2] for s in spans)
            y1 = max(s["bbox"][3] for s in spans)
            lines.append({
                "text": text, "spans": spans,
                "x0": x0, "y0": y0, "x1": x1, "y1": y1,
                "size": max(s["size"] for s in spans),
            })
    lines.sort(key=lambda L: (round(L["y0"]), L["x0"]))
    return lines


def grow_region(r, lines, pad=3.0):
    """Expand region to cover any text line whose bbox intersects it."""
    rr = fitz.Rect(r)
    for ln in lines:
        lb = fitz.Rect(ln["x0"], ln["y0"], ln["x1"], ln["y1"])
        if lb.intersects(fitz.Rect(rr.x0 - pad, rr.y0 - pad, rr.x1 + pad, rr.y1 + pad)):
            rr |= lb
    return rr


def line_in_regions(ln, regions, pad=2.0):
    lb = fitz.Rect(ln["x0"], ln["y0"], ln["x1"], ln["y1"])
    for r in regions:
        if lb.intersects(fitz.Rect(r.x0 - pad, r.y0 - pad, r.x1 + pad, r.y1 + pad)):
            return True
    return False


def render_svg(src_doc, page_idx, region, out_path):
    """Export a page region to a self-contained SVG (text as vector paths)."""
    tmp = fitz.open()
    p = tmp.new_page(width=region.width, height=region.height)
    p.show_pdf_page(fitz.Rect(0, 0, region.width, region.height),
                    src_doc, page_idx, clip=fitz.Rect(region))
    svg = p.get_svg_image(text_as_path=True)
    tmp.close()
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write(svg)


def render_line_spans(line):
    """Reconstruct line text with inline-code and bold wrapping (space-safe)."""
    parts = []
    for s in line["spans"]:
        t = s["text"]
        if not t:
            continue
        font = s["font"]
        size = s["size"]
        lead = t[: len(t) - len(t.lstrip())]
        trail = t[len(t.rstrip()):]
        core = t.strip()
        if not core:
            parts.append(t)
            continue
        if "Menlo" in font or "Mono" in font or size <= 10.5:
            core = "`" + core + "`"
        elif "Bold" in font:
            core = "**" + core + "**"
        parts.append(lead + core + trail)
    return "".join(parts)


def looks_like_list(text):
    t = text.strip()
    return t.startswith(("·", "•")) or (t.startswith("- ") and len(t) > 2)


def ends_terminal(text):
    t = text.rstrip()
    return bool(t) and t[-1] in TERMINAL


def join_para(a, b):
    if not a:
        return b
    if is_ascii_word(a[-1]) and is_ascii_word(b[0]):
        return a + " " + b
    return a + b


# ---------------------------------------------------------------------------
# Markdown assembly
# ---------------------------------------------------------------------------
def build_markdown(lines, img_refs_by_y):
    out = []
    i = 0
    img_idx = 0
    n = len(lines)
    while i < n:
        L = lines[i]
        kind = L["kind"]
        y = L["y0"]
        while img_idx < len(img_refs_by_y) and img_refs_by_y[img_idx][0] <= y:
            _, ref, label = img_refs_by_y[img_idx]
            out.append("")
            out.append(f"![{label}]({ref})")
            out.append("")
            img_idx += 1

        if kind in ("h1", "h2"):
            out.append("")
            out.append(("## " if kind == "h2" else "# ") + L["text"].strip())
            out.append("")
            i += 1
            continue

        if kind == "small":
            out.append("")
            out.append(L["text"].strip())
            out.append("")
            i += 1
            continue

        if kind == "code":
            block = []
            while i < n and lines[i]["kind"] == "code":
                block.append(lines[i])
                i += 1
            joined = []
            for ln in block:
                text = ln["text"]
                if not joined:
                    joined.append(text)
                    continue
                prev = joined[-1]
                stripped = text.lstrip()
                no_indent = (len(text) - len(stripped)) == 0
                cur_start = stripped[:1]
                prev_s = prev.lstrip()
                prev_complete = (prev.rstrip()[-1] in CODE_TERM) \
                    or prev_s.startswith(("#", "//", "@", "/*", "*", "import"))
                cur_continues = no_indent and cur_start not in "@} )];{#"
                if cur_continues and not prev_complete:
                    joined[-1] = join_para(prev, stripped)
                else:
                    joined.append(text)
            non_empty = [t for t in joined if t.strip()]
            if non_empty:
                min_ind = min(len(t) - len(t.lstrip()) for t in non_empty)
                joined = [t[min_ind:] if t.strip() else t for t in joined]
            code_text = "\n".join(t.rstrip() for t in joined).strip("\n")
            out.append("")
            out.append(f"```{_guess_lang(code_text)}")
            out.append(code_text)
            out.append("```")
            out.append("")
            continue

        if kind == "body":
            para = []
            while i < n and lines[i]["kind"] == "body":
                if looks_like_list(lines[i]["text"]):
                    break
                para.append(lines[i])
                i += 1
                if ends_terminal(para[-1]["text"]):
                    break
            if para:
                text = ""
                for idx, ln in enumerate(para):
                    seg = render_line_spans(ln).strip()
                    text = seg if idx == 0 else join_para(text, seg)
                out.append("")
                out.append(text)
                out.append("")
            items = []
            while i < n and lines[i]["kind"] == "body" and looks_like_list(lines[i]["text"]):
                items.append(render_line_spans(lines[i]).strip())
                i += 1
            if items:
                out.append("")
                for it in items:
                    out.append(f"- {it}")
                out.append("")
            continue

        i += 1

    while img_idx < len(img_refs_by_y):
        _, ref, label = img_refs_by_y[img_idx]
        out.append("")
        out.append(f"![{label}]({ref})")
        out.append("")
        img_idx += 1

    text = "\n".join(out)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip() + "\n"


def _guess_lang(code):
    if re.search(r"(public|private|void|class|import|@Override|Response|assert)", code):
        return "java"
    if re.search(r"^\s*[\w.-]+:\s", code, re.M) or re.search(r"^\s*-\s+name:", code, re.M):
        return "yaml"
    if re.search(r"^\s*(mvn|java|DEEPSEEK|export|cd)\b", code, re.M):
        return "bash"
    return ""


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    pdfs = sorted(glob.glob(os.path.join(DOC_DIR, "*.pdf")))
    # clear stale images from previous runs
    root_img = os.path.join(DOC_DIR, "images")
    if os.path.isdir(root_img):
        for fn in glob.glob(os.path.join(root_img, "**", "*.*"), recursive=True):
            try:
                os.remove(fn)
            except OSError:
                pass
    for pdf in pdfs:
        base = os.path.splitext(os.path.basename(pdf))[0]
        base = re.sub(r"\s*\(\d+\)\s*$", "", base)
        m = re.match(r"(第\d+节)", base)
        sec = m.group(1) if m else base
        img_dir = os.path.join(DOC_DIR, "images", sec)
        os.makedirs(img_dir, exist_ok=True)

        doc = fitz.open(pdf)
        md_parts = []
        img_counter = 0

        for pidx, page in enumerate(doc):
            all_lines = get_all_lines(page)
            raw_regions = detect_diagram_regions(page)
            regions = [grow_region(r, all_lines) for r in raw_regions]

            # classify lines, excluding those inside regions
            lines = []
            for ln in all_lines:
                if line_in_regions(ln, regions):
                    continue
                ln = dict(ln)
                ln["kind"] = classify(ln["size"])
                lines.append(ln)

            # render diagram images (SVG)
            img_refs = []
            for r in regions:
                img_counter += 1
                label = f"{sec} 图{img_counter}"
                fname = f"图-{img_counter:02d}.svg"
                fpath = os.path.join(img_dir, fname)
                render_svg(doc, pidx, r, fpath)
                ref = os.path.join("images", sec, fname).replace("\\", "/")
                img_refs.append((r.y0, ref, label))

            md_parts.append(build_markdown(lines, img_refs))

        doc.close()

        md_text = "\n".join(md_parts)
        md_text = re.sub(r"\n{3,}", "\n\n", md_text)
        md_path = os.path.join(DOC_DIR, base + ".md")
        with open(md_path, "w", encoding="utf-8") as fh:
            fh.write(md_text)
        print(f"OK {base}: {img_counter} images -> {os.path.basename(md_path)}")


if __name__ == "__main__":
    main()
