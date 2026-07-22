---
name: layered-arch-diagram
description: >-
  Creates colorful layered software architecture diagrams (channel → core engine →
  capability cards → storage) as SVG/PNG in the OryxOS visual style. Use when the
  user asks for an architecture diagram, system diagram, module map, 架构图, 分层图,
  or wants diagrams matching the layered colored-card layout (blue channels, yellow
  core pipeline, green/cyan/purple capability cards, pink storage).
---

# Layered Architecture Diagram

Generate **colorful, layered** architecture diagrams — not flat monochrome Mermaid boxes.

## When to use

- User wants an architecture / system / module diagram
- User says 架构图、分层图、系统图、版式参考 this skill
- README or docs need a visual that shows layers + internal pipelines

## Visual language (non-negotiable)

Copy the layout grammar of `assets/style-reference.png`:

| Layer | Role | Fill | Stroke | Shape |
|-------|------|------|--------|-------|
| Header | Product title + stack subtitle | — | — | Centered text |
| Channels | User/system entry points | `#3b82f6` / `#2563eb` | — | Rounded pill cards, **white title**, light subtitle |
| Core engine | Orchestration / main loop | `#fef9c3` | `#fde047` | Wide band; **inner white cards** in a horizontal pipeline with **orange** arrows |
| Capability | 2–4 peer services | Green `#dcfce7`, Cyan `#cffafe`, Purple `#ede9fe` | Matching mid-tones | Equal-width cards; title + `module-name` + white inner panel |
| Storage | Persistence | `#fce7f3` | `#f9a8d4` | Wide band; 1–2 white inner panels side by side |

### Typography

- Title ~28px bold dark (`#1a1a2e`)
- Section headers 15px bold
- Module id in monospace / muted (`oryxos-*`)
- Body 11–12px; use middle-dot `·` to pack lists
- Notes / footnotes smaller gray; optional `△` marker for caveats

### Flow rules

1. Top → bottom only (channels → core → capabilities → storage)
2. Peer channels connected by a **dashed** horizontal line
3. Core internal steps connected left→right with orange arrows
4. Soft drop shadow on major cards (`feDropShadow` or equivalent)
5. Background `#fafafa` or white — keep print/GitHub README friendly (light theme)

## Output workflow

1. **Gather content** from the project (modules, flows, storage). Do not invent systems.
2. **Prefer SVG** for docs (`docs/images/<name>.svg`). Also write PNG when the user asks or when embedding in slides.
3. **Keep a source** next to the asset when useful (`*.mmd` is OK for simple graphs; for this style prefer hand-authored SVG or GenerateImage with the reference).
4. **Update README / docs** to embed the image, e.g.:

```markdown
<p align="center">
  <img src="docs/images/architecture.png" alt="Architecture" width="900" />
</p>
```

Prefer **PNG** when the diagram is dense (GitHub SVG font/CSS quirks). Prefer **SVG** when it must scale crisply and fonts are system-safe.

5. After writing an image file, **Read the image** and visually QA: overlapping text, cut-off cards, weak contrast, missing arrows.

## Generation options

### A. Hand-authored SVG (recommended for repo docs)

Use the template structure:

```
header
channel row (2 cards + dashed link)
↓
core band (N white pipeline cards + orange arrows)
↓
capability row (2–4 tinted cards)
↓
storage band (SQLite / files / …)
```

Reuse colors from the table above. Keep viewBox width ~1000–1200, height sized to content.

### B. GenerateImage tool

When using image generation:

- Attach `assets/style-reference.png` as `reference_image_paths`
- Prompt must name: title, subtitle, each layer label, card titles, bullet lines, arrow direction
- Ask for light background, rounded cards, no decorative clutter, no watermarks
- Save under the path the user requested (usually `docs/images/`)

### C. Do not

- Do not replace this style with default gray Mermaid subgraphs unless the user explicitly wants Mermaid-only
- Do not use dark neon / purple-glow “AI slide” aesthetics
- Do not put stats, badges, or marketing chips on the diagram

## Content checklist

- [ ] Product name + one-line tech stack in header
- [ ] Every entry channel shown
- [ ] Core loop / pipeline steps labeled
- [ ] Capability modules named with package/module id
- [ ] Persistence called out (DB + files if both exist)
- [ ] Day-one audit / security notes only if true for the project

## Example prompt skeleton (GenerateImage)

```
Architecture diagram, light background #fafafa, clean sans-serif.
Title: "<Product> — Architecture"
Subtitle: "<one line>"
Top: two blue rounded cards "CLI …" and "Web …" with dashed link.
Middle: wide yellow band "CORE ENGINE — <module>" with horizontal white boxes
  connected by orange arrows: <step1> → <step2> → …
Below: three cards green/cyan/purple for <A>, <B>, <C> with white inner panels.
Bottom: wide pink band "STORAGE — <module>" with two white panels.
Top-down arrows between layers. No watermarks.
```
