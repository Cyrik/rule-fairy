# Configuration

Rule Fairy reads `.cursor/rules/**/*.mdc` without configuration. To change
the defaults, create `rule-fairy.edn` at the project root. It contains one
EDN map; combine the entries below in that map as needed.

For a first rule and installation instructions, see the
[README](../README.md). [Design](design.md) states the delivery contracts.

## Rule directories and extensions

```clojure
{:rules {:dirs [".cursor/rules" "docs/rules"]
         :extensions [".mdc" ".md"]}}
```

Each supplied key replaces its default. To add a directory, include the
default `.cursor/rules` directory in the list, as above. Directories are
relative to the project root. Rule Fairy reads them recursively; any file
with a listed extension is a rule.

Both keys are optional. The defaults are `:dirs [".cursor/rules"]` and
`:extensions [".mdc"]`. Unknown keys in `:rules` are rejected. Rule names
are relative to their own directory, so the same name in two directories
is an error.

The frontmatter fields are Cursor's `description`, `globs` and
`alwaysApply`, plus Rule Fairy's `promptAnyOf` and `promptRequires`. See
[Writing rules](../reference/mdc.mdc) for their format. Cursor ignores the
keyword fields and includes `@path` imports as whole files rather than
sections. Keep rules valid for Cursor if you use it; see the compatibility
limit in [Design](design.md#limits).

## Shell changes

```clojure
{:shell-hook {:claude true :codex true}}
```

The defaults are `:claude true` and `:codex false`. Set either to `false`
to disable its shell check. Codex makes many shell calls and rarely edits
through them, so its check is optional to avoid that cost on every call.
Enable it in projects where Codex uses shell commands to write files.

The check uses Git to find working-tree changes. A disabled hook still
starts, but exits before any Git call. See [Delivery](design.md#delivery)
for the check's timing, pending changes and limits.

## Prompt blocks to ignore

```clojure
{:prompt {:ignored-blocks ["system" "instructions"]}}
```

By default, keyword matching ignores text inside closed `<system>` blocks.
An orchestrator can put its own instructions there and leave the person's
task outside. The surrounding task text still selects rules.

The list contains tag names, without angle brackets, and replaces the
default `["system"]`. An empty list matches the whole prompt. This setting
affects keyword matching; it does not disable `alwaysApply` rules. On
Claude Code, prompts composed by another agent or process are skipped as
described in [Delivery](design.md#delivery).

## Instruction files for review

The review bundle reads instruction files in the project root and in each
directory above a changed file. Its default filename order is:

1. `AGENTS.override.md`
2. `AGENTS.md`
3. `.claude/AGENTS.md`
4. `CLAUDE.md`
5. `.claude/CLAUDE.md`
6. `CLAUDE.local.md`

It follows their `@path` imports the way Claude Code does and includes each
real file once. An `AGENTS.md` beside an `AGENTS.override.md` is left out
when a CLAUDE file exists at the root or in that directory: Codex reads
only the override, and Claude Code then reads only its CLAUDE files.

To restrict or reorder the files:

```clojure
{:instructions {:files ["AGENTS.md" "CLAUDE.md"]}}
```

An empty vector includes no instruction files. Order matters: when two
files share a section or a long paragraph, the earlier one keeps it. The
later file gets a line naming the kept copy.

## State and delivery labels

Hooks store session state and glob caches in `.rule-fairy/claude/` and
`.rule-fairy/codex/`. Review artifacts go in `.rule-fairy/review/<mode>/`.
Each directory ignores its own content in Git; you do not need to change
the project's ignore rules.

Injected context uses these labels:

- `[rule-fairy injected: <rule>]`
- `[rule-fairy matched: alwaysApply ...]`
- `[rule-fairy matched: keyword ...]`
- `[rule-fairy matched: glob on <path>]`
- `[rule-fairy shard n/m <delivery>]`
- `[rule-fairy deferred: ...]`
- `[rule-fairy error: ...]`

The hooks also record the plugin's location in
`.rule-fairy/<harness>/plugin-root`. See
[Using the review from another skill](review-from-a-skill.md) to use that
path from a project's own skill.
