# Prep: what Brian does before (and between) Claude Code sessions

This document is for Brian, not for Claude Code sessions. The engineering
context lives in `01-overview-design-and-laws.md`; the per-milestone task
specs are the `1x-milestone-*.md` files. Your `~/.claude/CLAUDE.md` loads
automatically in every session, so the plan docs only restate its rules where
the plan applies or refines them (cross-building, testing stack, TDD vs. the
frozen-laws rule).

## 1. Vendor the upstream reference sources (do this before M0)

The single biggest de-risking step for the macro milestones is giving the
model upstream code to *read and adapt* instead of asking it to recall the
compiler APIs from memory.

Create a `reference/upstream/` directory in the natchez-tagless repo that is
**excluded from compilation** (do not wire it into sbt; it is read-only
reference material). Populate it from cats-tagless at tag `v0.16.5`:

```
git clone --depth 1 --branch v0.16.5 https://github.com/typelevel/cats-tagless
```

Copy, preserving relative paths under `reference/upstream/`:

1. **Scala 3 macro reference:** `cats/tagless/macros/MacroAspect.scala` (from
   the macros module's `scala-3` source tree), plus every helper it imports
   from the `cats.tagless.macros` package, transitively (there is shared
   derivation machinery in that package — copy whatever `MacroAspect`
   touches). It does not need to compile in our repo; it needs to be readable.
2. **Scala 2 macro reference:** the Scala 2 derivation source (in 0.16.x this
   is a `DeriveMacros`-style file in the macros module's `scala-2` tree —
   locate the file containing the `aspect` derivation and copy it whole,
   plus any utilities it depends on).
3. **API reference:** `cats/tagless/aop/Aspect.scala` (the `Weave`/`Advice`
   definitions — the milestone docs tell the model to verify constructor
   signatures against this file rather than guessing).
4. **Laws style reference:** the `cats-tagless-laws` sources for `FunctorK`
   and `Aspect` (law traits + discipline `Tests` classes), so M2 matches
   upstream conventions.

Add a `reference/upstream/README.md` recording: source repo URL, tag
`v0.16.5`, the commit SHA, and the license (cats-tagless is Apache-2.0 —
retain the license headers in the copied files; when macro code is *adapted*
into our namespace in M3/M4, the milestone docs already instruct the model to
carry attribution headers, and you should confirm this in review for license
compliance).

If you'd rather not do the copying yourself, it is acceptable to make this
the first task of the M0 session — but do it as an explicit, reviewed step,
and verify the SHA/tag yourself before merging.

## 2. Pin versions and record repo facts

Before M0, decide/verify and note (you'll paste these into the M0 session):

- cats-tagless `0.16.5` (decided; publishes 2.12, 2.13, and 3, so it does not
  constrain our axes).
- cats-mtl: the repo already depends on 1.4.0, which satisfies the
  `Handle.allow`/`rescue` requirement for the M5 integration tests.
- Cross-building (decided, per your CLAUDE.md rule and current repo
  practice): **2.12, 2.13, and Scala 3 LTS**. natchez-tagless currently
  publishes 2.12 and 2.13; verify whether it already publishes a Scala 3 axis
  — if not, M0 adds one for the new modules, which is worth confirming is
  what you want before that session. If 2.12 ever has to be dropped (e.g. a
  dependency drops it), that is your call and gets called out explicitly, per
  CLAUDE.md.
- Test stack: the repo already uses MUnit + munit-cats-effect + ScalaCheck,
  which matches your CLAUDE.md preferences; M2 adds the munit-discipline
  bridge for law RuleSets. Property-based style throughout.
- Scala 3 stays on the LTS line (decided). Consequence, matching upstream
  cats-tagless: the Scala 3 derivation entry points are `@experimental`
  because `Symbol.newClass` is experimental there, so declaring a derived
  instance on Scala 3 requires `@experimental` at the call site or
  `-experimental` (3.4+) — this lands on end users' algebra companions and is
  documented for them in M5. Revisit only if a future Scala LTS stabilizes
  the needed reflect APIs.
- Confirm kind-projector setup for 2.12/2.13 and `-Ykind-projector` for
  Scala 3, since the core sources use `*` type lambdas (your CLAUDE.md
  already notes the confusing failure mode when the plugin is missing).

## 3. Model routing

Suggested assignment (adjust to what's available on your plan):

| Milestone | Suggested model | Rough size (LoC, frontier-LLM-authored) | Why |
|---|---|---|---|
| M0 scaffolding | mid-tier (Sonnet-class) | ~150–300 (build config) | Mechanical sbt/CI work |
| M1 runtime core + reference instance | strongest available (Opus 4.8+) | ~400–600 | The reference instance is the differential oracle everything else diffs against; it must be exactly right |
| M2 laws + test kit | mid-tier, strongest if budget allows | ~700–1,000 | Well-specified, but subtle Eq/rendering plumbing |
| M3 Scala 2 macro | strongest available | ~600–900 adapted + ~300 tests | Compiler-API metaprogramming; unforgiving failure modes |
| M4 Scala 3 macro | strongest available | ~600–900 adapted + ~300 tests | Same, plus `Symbol.newClass` sharp edges |
| M5 natchez integration | mid-tier | ~300–500 + docs | Plumbing + docs; integration test is the only subtle part |

(Size estimates are rough, per-milestone totals across main and test sources;
treat them as routing signal, not commitments.)

## 4. Session protocol

- Commit these plan documents into the repo (e.g. `docs/plans/raise-aspect/`)
  before M0, per your session-lifecycle rule that progress lives in commits
  and plan docs, never only in conversation memory. Each milestone doc gets a
  short `## Status` section at the top that the executing session updates
  when it finishes (state, next step, anything punted) — that's also what
  makes long sessions safely retirable.
- One milestone per session, per branch, per PR. Sessions read the overview
  plus exactly one milestone doc at start (they're in the repo, so "read the
  plan doc first" applies instead of pasting).
- Per your preference, milestone sessions should orchestrate via the
  superpowers subagent-driven-development skill where it's available, keeping
  the orchestrating context small; and start M3/M4 in Plan Mode (multi-phase,
  architecture-adjacent work) while the other milestones can proceed
  directly.
- Do not start milestone N+1 until milestone N's acceptance criteria have
  been demonstrated with actual test output in CI, not just claimed.
- Human review gates — do these yourself, they are the checkpoints least
  amenable to delegation:
  - **After M1:** diff the hand-written reference instance against the
    expansion spec in the overview, line by line. Everything downstream
    (including both macros) is validated by agreement with this instance.
  - **After M3 and M4:** review the macro code specifically for: preservation
    of parameter-list shape and `implicit`/`using` flags, by-name modifiers,
    dealiasing of capability parameter types, and use of the declared
    `Raise[F, -E]` type verbatim (variance drift is the classic silent bug).
    Also confirm attribution headers on adapted upstream code.

## 5. Enforce law immutability

After M2 merges, treat the `raise-aspect-laws` module as **frozen**. The
milestone docs for M3/M4 instruct the model that it may add new test files
(e.g., compile-error suites) but must not modify existing laws or tests, and
that if it believes a law or test is wrong it must stop and flag it rather
than edit it. Your enforcement side:

- In M3/M4 review, run `git diff` scoped to the laws module and the M2 test
  sources; it should be empty apart from clearly additive new files.
- Per your rule that prose instructions guide behavior but are not a security
  boundary, back the freeze with hard enforcement: a PreToolUse hook (or
  permissions rule) in the M3/M4 sessions denying edits to existing files
  under `modules/raise-aspect-laws/` and the M1 fixture sources (new files
  allowed), plus a CI check that fails any PR modifying those paths unless it
  carries an explicit `laws-change` label — so a deliberate, reviewed law fix
  remains possible but a quiet one is not.
- If the model flags a law as suspect, evaluate it yourself (or bring it back
  to a design discussion) — do not let the implementing session adjudicate
  its own spec.

## 6. Decisions already made (for your reference)

These are baked into the overview; no action needed, listed so you can spot
drift in review: standalone modules inside the natchez-tagless build under
`com.dwolla` coordinates (upstreaming to cats-tagless deferred); concrete
names (`RaiseAspect`, `RaiseFunctorK`, `RaiseArrow`, `RaisePull`,
`Synthetic`); `weave` keeps its `Functor[F]` constraint; shell `Weave`s from
`raiseLift` use the fixed placeholder name `"raise"`; cats-tagless pinned to
`v0.16.5` with `cats.tagless.macros.MacroAspect` as the Scala 3 reference;
cross-building targets 2.12, 2.13, and Scala 3 LTS, with dropping 2.12 being
an explicit, called-out decision reserved to you; Scala 3 stays on the LTS
line, with the derivation entry points annotated `@experimental` and that
requirement documented for users (see §2).
