---
name: review-plan
description: Reviews an implementation plan against the repository's explicit rules before any code exists, by working out which files the plan will create or edit, collecting the rules that apply to those paths, and checking every step against them. Use when asked to review, check or sanity-check a plan, a design document or a task breakdown against project rules or conventions. Read-only; never edits the plan.
allowed-tools: Bash(${CLAUDE_SKILL_DIR}/scripts/run *)
---

# Review a plan against repository rules

Checks a plan the way `rule-fairy:review` checks a diff, before the code is
written: the paths the plan intends to touch select the rules, and each step
is judged against them. Only violations backed by an explicit rule are
reported. Fits a make-plan, review, execute loop and can be rerun after the
plan is edited.

`<skill-dir>` below is this skill's directory. In Claude Code it is
`${CLAUDE_SKILL_DIR}`; in Codex it is the directory holding this SKILL.md.
Run every command from the reviewed project's root. The scripts need
Babashka (`bb`).

## 1. Find the plan

The plan is a Markdown file named in the request, given relative to the
project root or as an absolute path; it need not be inside the checkout.
Ask when it is unclear which file is meant.

## 2. Check which paths the plan touches

```sh
"<skill-dir>/scripts/run" paths <plan-file>
```

Prints the paths the bundle will be built for and where they came from. Every
section headed like "Files" or "Files to change" in the plan wins, in document
order, and is taken as written. Otherwise every path-like token in the text counts when it
exists in the checkout or its parent directory does; the rest are listed as
dropped. An absolute path under the project counts as its path from the
root; one outside the project is dropped, since no rule of the project can
cover it. A path naming an existing directory stands for the files under it,
and the output says which directories were expanded.

Read the plan yourself and compare. Add anything the heuristic missed, such
as a file in a directory that does not exist yet, with `--paths <path>...`
on the next command, and say in the report that you added them. A path that
is missing means rules that were never checked.

## 3. Build the rules bundle

```sh
"<skill-dir>/scripts/run" bundle <plan-file> [--paths <path>...]
```

Writes `.rule-fairy/review/plan/rules.md` and `meta.json`, replacing
whatever an earlier pass left there. The bundle holds the instruction files
that apply to the paths, every rule whose globs match them plus every
`alwaysApply` rule, and the documentation those rules import, deduplicated;
its header lists what went in. `meta.json` records the paths, their source,
any you added, and the rules revision.

Run it once per pass. If the command fails, report the failure; a missing
bundle is not evidence that no rules apply.

## 4. Review

In Claude Code, run the `rule-fairy:rules-reviewer` agent with this prompt,
placeholders filled in with absolute paths:

```text
Review the plan at <plan-file> against the rules bundle at
<root>/.rule-fairy/review/plan/rules.md, using the path list in
<root>/.rule-fairy/review/plan/meta.json. Follow the procedure in
<skill-dir>/../review/references/procedure.md exactly, including its plan
review section. Read the complete bundle before judging anything.
```

Elsewhere, follow
[../review/references/procedure.md](../review/references/procedure.md)
yourself, in full, with the same three files.

## 5. Report

Return the findings to the user in the shape the procedure specifies. Name
the paths the bundle was built for and any you added. This skill does not
edit the plan, write code, commit, or post anywhere. When asked to write a
report file, choose a new path rather than overwrite an existing one.

## 6. Clean up

```sh
"<skill-dir>/scripts/run" clean
```

Removes this pass's artifacts once the report is out. The next pass, after
the plan is edited, starts from scratch.
