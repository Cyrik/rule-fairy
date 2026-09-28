---
name: review
description: Reviews a diff, branch or pull request against the repository's explicit rules (Cursor-style rule files plus AGENTS.md, CLAUDE.md and their relatives) and reports only violations a rule supports, with the rule quoted. Use when asked to review changes, a PR, a branch or uncommitted work for rule violations or rule compliance. Never edits code, commits, or posts a review.
allowed-tools: Bash(${CLAUDE_SKILL_DIR}/scripts/run *)
---

# Review changes against repository rules

Checks a diff against the rules that apply to the files it touches: the
project's rule files (`.cursor/rules/**/*.mdc` unless `rule-fairy.edn` says
otherwise) and the harness instruction files (`AGENTS.md`, `CLAUDE.md` and
relatives, with their imports). Only violations backed by an explicit rule
are reported. General bug review, lint and tests are not part of this skill.

`<skill-dir>` below is this skill's directory. In Claude Code it is
`${CLAUDE_SKILL_DIR}`; in Codex it is the directory holding this SKILL.md.
Run every command from the reviewed project's root. The scripts need
Babashka (`bb`); PR mode also needs the GitHub CLI (`gh`), logged in.

## 1. Fetch the diff

Pick the source from the request; ask when it is unclear.

| Request | Command |
| --- | --- |
| A pull request number or URL | `"<skill-dir>/scripts/run" fetch --pr <number>` |
| Uncommitted work, "my changes" | `"<skill-dir>/scripts/run" fetch --local` |
| A branch against its base | `"<skill-dir>/scripts/run" fetch --base <ref>` |

The command writes `.rule-fairy/review/diff/patch.diff` and `meta.json`,
replacing whatever an earlier run left there. `--local` and `--base` cover
staged, unstaged and untracked files and detect renames. `--pr` reviews the
patch only, against the rules in the current working tree. Add `--checkout`
only when the user has explicitly authorised changing the checkout; it runs
`gh pr checkout --detach` first.

Run it once per review. The artifacts belong to this run and step 6 removes
them.

## 2. Build the rules bundle

```sh
"<skill-dir>/scripts/run" bundle
```

Writes `.rule-fairy/review/diff/rules.md`: the instruction files that apply
to the changed paths, every rule whose globs match them plus every
`alwaysApply` rule, and the documentation those rules import, deduplicated.
Its header lists what went in.

If the command fails, report the failure. A missing or partial bundle is not
evidence that no rules apply.

## 3. Note the revisions

Read `meta.json`. `head_ref_oid` is the code under review. `rules.head_ref_oid`
and `rules.clean` say which guidance was used. The report must state a
mismatch: a PR reviewed without `--checkout` is checked against the working
tree's rules, and `rules.clean: false` means guidance that is not what is
committed: modified, untracked or ignored rules, `rule-fairy.edn`, or an
instruction file or imported document the bundle used. `rules.clean: null`
means unknown: the project is outside git, or some of that guidance lies
outside the checkout, reached through a link or a configured directory.
The README's design note on `rules.clean` is the canonical statement.

## 4. Review

In Claude Code, run the `rule-fairy:rules-reviewer` agent with this prompt,
placeholders filled in with absolute paths:

```text
Review the patch at <root>/.rule-fairy/review/diff/patch.diff against the
rules bundle at <root>/.rule-fairy/review/diff/rules.md, using the revision
metadata in <root>/.rule-fairy/review/diff/meta.json. Follow the procedure in
<skill-dir>/references/procedure.md exactly. Read the complete bundle before
judging anything.
```

Elsewhere, follow [references/procedure.md](references/procedure.md)
yourself, in full, with the same three files.

## 5. Report

Return the findings to the user in the shape the procedure specifies. This
skill does not fix code, change the checkout beyond an authorised
`--checkout`, commit, or post a review to GitHub. When asked to write a
report file, choose a new path rather than overwrite an existing one.

## 6. Clean up

```sh
"<skill-dir>/scripts/run" clean
```

Removes this run's artifacts once the report is out, so a later review
cannot pick up this one's inputs by mistake.
