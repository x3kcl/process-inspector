# Presentation — starting an AI project

`starting-an-ai-project.pptx` — a 35-slide management/stakeholder deck that uses the Process
Inspector as the worked example for **how to start an AI project**: the research that
precedes the first prompt, the rules that make the speed safe, eight embedded feature
videos, what the whole thing cost in model spend, and what the research refused to let ship.
16:9 (13.333 × 7.5 in), ~27 MB with the videos embedded.

It replaces the earlier `process-inspector-delivery-review.pptx`, which was the same material
framed as a delivery retrospective.

## Structure

| Part | Slides | What it covers |
|---|---|---|
| — | 1 – 4 | The question, the worked example, the outcome numbers |
| **One — how to start an AI project** | 5 – 14 | The seven-step playbook and the timeline |
| **Two — what the method produced** | 15 – 23 | Seven embedded feature videos |
| **Three — the consequences** | 24 – 30 | Competitor + scientific research, the ledger video, and the gates |
| **Four — cost and lessons** | 31 – 35 | The measured API bill, what cost us time, what we change |

## Everything here is generated

Nothing is hand-edited. Four artefacts, four commands:

```bash
npm i pptxgenjs                                  # not a project dependency; node_modules/ is gitignored

bash seed-demo-for-video.sh                      # 1. stage the demo population the clips need
node record-clips.mjs                            # 2. drive the live demo, record .webm per clip
node make-titles.mjs && bash make-videos.sh      # 3. title cards + posters -> videos/*.mp4
node build-deck.mjs starting-an-ai-project.pptx  # 4. the deck
```

`clips.mjs` is the single source of truth for the clip list — name, poster timestamp, and the
caption text used on both the title card and the slide. Add a clip there, add a `clips.<name>`
function in `record-clips.mjs`, and the other three steps pick it up.

Steps 1–3 need the live demo reachable and the demo compose stack up (the seeder talks to the
engines through a throwaway container on `process-inspector-demo_internal`).

**Rebuilding the deck from a fresh clone needs neither** — `videos/` is committed, and the
poster frames `build-deck.mjs` needs are extracted from those mp4s, not from the gitignored
recordings:

```bash
bash make-videos.sh --posters-only                # videos/*.mp4 -> .video/poster/*.png
node build-deck.mjs starting-an-ai-project.pptx
```

## The videos

Eight clips, 24–59 s, recorded against **the live demo** — three real Flowable engines (two
6.8, one 7.1) carrying two months of accumulated history. Two of them really mutate it: the
`fix` clip corrects a variable and retries a job until the instance reaches COMPLETED on
camera, and `bulk` dispatches a real error-class retry over nine dead-lettered jobs.

Recorded with Playwright at 1600×900, encoded H.264 + silent AAC in mp4 — PowerPoint plays
that; it does **not** play the VP8/WebM Playwright records. `videos/*.mp4` are also usable
on their own, for onboarding or a ticket.

See [DEMO-SEED-WINDOW.md](DEMO-SEED-WINDOW.md): the staged instances and the on-camera
corrective actions land in the same pilot dataset that the R1/R2 data-maturity gates mine,
and must be excluded from those measurements.

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
       --outdir /data/.render /data/starting-an-ai-project.pptx \
   && pdftoppm -r 80 -png /data/.render/starting-an-ai-project.pdf /data/.render/s'
```

LibreOffice renders each video as its **cover image**, so the render also verifies the poster
frames. It cannot verify playback — that needs real PowerPoint.

## Traps that each cost a rebuild

**Deck**

- **`pptx.layout` must be `LAYOUT_WIDE`** (13.333 × 7.5 in). pptxgenjs's `LAYOUT_16x9` is the
  legacy 10 × 5.625 in page — same aspect ratio, so it looks plausible until every coordinate
  silently overflows the right edge.
- **Don't use the pptxgenjs `bullet` option** for items with a bold lead-in. `bullet` is a
  paragraph property, so the lead-in run and the body run each become their own bulleted
  paragraph and the item visibly splits in two. The dash is drawn inline instead.
- **`addMedia` without `cover` shows a grey play button**, not the video's first frame. Pass a
  base64 data URI (it throws without the `data:image/png;base64,` header).
- **Section titles need a two-line box.** A one-line height lets a wrapped title overrun the
  rule beneath it, which then strikes through the second line.

**Recording**

- **Playwright films the page, not the pointer.** There is no cursor in the output unless you
  paint one — `record-clips.mjs` injects one and every helper moves it.
- **The instance-detail page does not scroll.** The document is exactly viewport-height and
  the tab panel is an inner `section.tab-body` that the diagram squeezes to ~24 px, so
  `page.mouse.wheel` does nothing and clips silently film the un-scrolled page. Collapse the
  diagram first (`hideDiagram()`) — which is also how an operator works the page.
- **`?tab=compare` does not select the Compare tab**; click the why-stuck strip's own button.
- **The bulk modal's reason field is an unlabelled `<textarea>`.** `getByRole('textbox').last()`
  grabs the Ticket ID input, leaves the reason empty, and the dispatch button stays locked
  behind "Reason too short" while the click fights the modal backdrop.
- **Don't click the Operations drawer straight after a dispatch** — it blocks for ~60 s on the
  closing modal's backdrop. Reload first; the job is already persisted.
- **Playwright's bundled ffmpeg is libvpx-only** (no libx264), so encoding goes through a
  containerised full ffmpeg.

## Figures

Slide 4 and the cost slide are counted from this repository and from the project's own agent
session logs, on **14 September 2026**. Re-count before reusing the deck:

| Figure | Source |
|---|---|
| 143 pull requests · 582 commits | `git log` on the current branch |
| 128k lines of product code | `backend/src` `*.java` + `frontend/{src,e2e}` `*.ts(x)` |
| 2,450 automated tests · 276 real-engine | `@Test` counts + `*IT.java` |
| 119 requirements · 21,600 doc lines | distinct `R-*` ids and `docs/**/*.md` |
| $343 · 494M tokens · 1,627 calls | `~/.claude/projects/-home-flapci-workspace-process-inspector/*.jsonl`, priced at public list rates |

The CI timing figure (~25 min → 3 m 45) comes from the runner-slot parallelisation work, not
from a file in this repo. The **$343 covers 4 August – 14 September only** — the surviving
session logs do not reach back to the July build phase, which is why the whole-project figure
on the cost slide is given as a range with the measured number as its anchor.
