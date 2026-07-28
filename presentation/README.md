# Presentation — delivery review

`process-inspector-delivery-review.pptx` — a 15-slide management/stakeholder deck on how the
Process Inspector was built: the research that preceded the code, the delivery approach, the
quality evidence, and the workflow changes we took away from it. 16:9 (13.333 × 7.5 in).

The `.pptx` is **generated**, not hand-edited. Edit `build-deck.mjs` and regenerate, so the
deck and its source never drift apart:

```bash
npm i pptxgenjs            # in a scratch dir; not a project dependency
node build-deck.mjs process-inspector-delivery-review.pptx
```

## Verifying the layout

There is no PowerPoint on the build hosts, so layout is checked by rendering in a container
(LibreOffice + Carlito, which is metric-compatible with Calibri — so wrapping and overflow
match what PowerPoint will do):

```bash
docker build -t pi-lorender:1 - <<'EOF'
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      libreoffice-impress libreoffice-core \
      fonts-crosextra-carlito fonts-crosextra-caladea fonts-dejavu-core \
      poppler-utils \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /data
EOF

mkdir -p .render && docker run --rm -v "$PWD":/data -u "$(id -u):$(id -g)" pi-lorender:1 sh -c \
  'soffice -env:UserInstallation=file:///tmp/lou --headless --convert-to pdf \
       --outdir /data/.render /data/process-inspector-delivery-review.pptx \
   && pdftoppm -r 80 -png /data/.render/process-inspector-delivery-review.pdf /data/.render/s'
```

`.render/` is gitignored.

## Two traps that cost a rebuild

- **`pptx.layout` must be `LAYOUT_WIDE`** (13.333 × 7.5 in). pptxgenjs's `LAYOUT_16x9` is the
  legacy 10 × 5.625 in page — same aspect ratio, so it looks plausible until every coordinate
  silently overflows the right edge.
- **Don't use the pptxgenjs `bullet` option** for items with a bold lead-in. `bullet` is a
  paragraph property, so the lead-in run and the body run each become their own bulleted
  paragraph and the item visibly splits in two. The dash is drawn inline instead.

## Figures

The numbers on slides 3, 4 and 12 are counted from this repository (commits, merged PRs,
source lines, `@Test` methods, e2e specs, `R-*` requirement IDs, doc line counts) as of
2026-07-27. Re-count before reusing the deck. The CI timing figure (~25 min → 3m45) comes
from the runner-slot parallelisation work, not from a file in this repo.
