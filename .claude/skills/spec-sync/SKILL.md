---
name: spec-sync
description: docs/SPECIFICATION.md, ARCHITECTURE.md and IMPLEMENTATION-PLAN.md move in lockstep with every behavior change — the WHAT/WHY/WHEN division across the three docs. Read before declaring any behavior change done.
---
# Specification & documentation lockstep (process-inspector)

*Ported from the flap `spec-sync` skill; adapted to this repo's three-doc layout.*

## 🎯 When to use
**Every change session that alters behavior** — features, endpoints, filters, corrective
actions, registry fields, RBAC rules, docker harness. Read this BEFORE declaring any such
change "done". A behavior change that ships without its doc delta is an incomplete change.

## 🛑 Primary directive
The three docs are the living specification — code and docs move in the SAME change, in BOTH
directions:

| Doc | Owns | Update when… |
|---|---|---|
| `docs/SPECIFICATION.md` | WHAT: features, filters, corrective actions + their REST mappings, non-goals | any user-visible capability changes |
| `docs/ARCHITECTURE.md` | WHY/HOW: topology, composite IDs, fan-out & partial results, status join, registry data model, BFF API table (§4), RBAC/audit/safety rails (§5) | any endpoint, DTO shape, join rule, registry field, or cross-cutting rule changes |
| `docs/IMPLEMENTATION-PLAN.md` | WHEN: milestone scope + done-criteria | scope moves between milestones, or a milestone lands (mark it) |
| `README.md` | RUN: quick start, ports, env vars | any run/setup step changes |

1. **Spec-driven** (the user edited a doc): `git diff docs/`, derive the behavioral delta,
   implement exactly that delta. If reality forces a deviation, correct the doc text in the
   same change — never leave spec and code disagreeing.
2. **Chat-driven** (feature/fix requested in conversation): before finishing, update the
   matching sections. New corrective action → new row in SPECIFICATION §D **and** the
   ARCHITECTURE §4 API table. New registry field → the YAML block in ARCHITECTURE §3.

## Conventions
- Exact strings are the spec: REST paths, action names, status values, YAML keys, role names.
  When you change one in code, grep all three docs for the old value.
- Terse present-tense rules; no implementation narration, no history ("previously…").
- The ARCHITECTURE §4 endpoint table is the single index of the BFF surface — a new
  controller method without a row there is a doc bug.
