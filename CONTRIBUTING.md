# Contributing

[docs/design.md](docs/design.md) states what the hooks and the review
guarantee; a change in behaviour changes that file first.
[reference/agent_hooks.mdc](reference/agent_hooks.mdc) documents the hook
machinery for agents working in this repository, and
[reference/mdc.mdc](reference/mdc.mdc) the rule file format.

## Layout

- `.claude-plugin/plugin.json`, `.codex-plugin/plugin.json`: the manifests.
  `.claude-plugin/` is Claude Code's; `.codex-plugin/` is Codex's, carrying
  the hooks registration and the listing interface, with `skills/`
  discovered by convention. There is deliberately no portable root
  `plugin.json`: Codex CLI 0.154.0 loads no hooks at all from a package that
  has one, whether they are declared in the overlay, in the root file or in
  its `extensions.com.openai` block, although its docs say the overlay
  applies. `.claude-plugin/marketplace.json` makes the repository its own
  marketplace for both harnesses. The three files carry the same version,
  bumped with every push meant for installed users, because Claude Code's
  `plugin update` acts only on a version change.
- `hooks/hooks.json`, `hooks/codex-hooks.json`: the Claude Code hooks
  manifest names the mod's module; the Codex one registers commands. Every
  process the hooks start runs `hooks/run`, a small shell wrapper that finds
  the plugin root, hands it to the script as `RULE_FAIRY_PLUGIN_ROOT`, makes
  sure `bb` is on the PATH, and starts Babashka with the plugin's own
  `bb.edn` so a consuming repository's `bb.edn` never reaches the classpath.
- `hooks/claude/`: `register.ts`, the mod, one in-process hook per event
  that runs `mod.bb` once per delivery and attaches what it prints;
  `mod.bb`, the entry point, which selects the rules for a prompt, a
  subagent's task, an edit or a shell command; `common.bb` (project root,
  session state, the delivery, the glob cache, shell-change detection);
  `common_test.bb`; and `register.test.ts`, the mod's tests under
  `claude plugin test`.
- `hooks/codex/`: `common.bb`, `prompt.bb`, `post_edit.bb` (edits and shell
  commands alike), and `common_test.bb`. The adapters intentionally have
  different delivery contracts.
- `src/rule_fairy/rules.clj`: the engine. Rules source, frontmatter parsing,
  glob and prompt matching, documentation imports with overlap merging,
  rendering, block splitting, and the fitting of a delivery to its budget.
  `markdown.clj` holds the line-level Markdown structure it shares with the
  bundle code (fences, headings, sections, units); `config.clj` reads
  `rule-fairy.edn`.
- `src/rule_fairy/instructions.clj`: harness instruction files for the review
  bundle. `AGENTS.md`, `CLAUDE.md` and relatives per touched directory,
  `@path` imports the way Claude Code follows them, block comments stripped,
  one copy per real file. `src/rule_fairy/dedup.clj`: section and paragraph
  deduplication across a bundle, each omission replaced by a line naming the
  kept copy.
- `src/rule_fairy/session_state.clj`: per-session state shared by the
  adapters: the shell-check marker and pending paths for both, Codex's
  transcript metrics, Claude Code's delivery times.
  `src/rule_fairy/transcript.clj`: incremental compaction counting with a
  scan cursor for Codex, and the reader that names the rules the whole
  deliveries among a set of frames injected, for Claude Code.
  `src/rule_fairy/changes.clj`: the files changed in a checkout since a
  moment, from `git status` plus modification and inode change times, which
  is how the shell hook finds what a command wrote.
- `src/rule_fairy/bundle.clj`: the review bundle. Instruction files, matching
  and always-on rules and their documentation for a path list, deduplicated,
  rendered with a header naming what went in, and written under the
  self-ignored `.rule-fairy/review/<mode>/`, plus the rules revision recorded
  next to it. `plan.clj` extracts the paths a plan intends to touch; `git.clj`
  is the small amount of git the review scripts share.
- `skills/review/`: the `rule-fairy:review` skill, discovered by both
  harnesses from this directory. `SKILL.md` is what the agent follows;
  `scripts/run` starts `scripts/review.bb` with the plugin's own `bb.edn`.
  `review fetch` takes a pull request through `gh`, the working tree against
  HEAD, or everything since the merge base with a branch, and writes
  `patch.diff` with a `meta.json` naming the code revision, the rules
  revision and the changed paths. The working-tree modes stage the tree into
  a throwaway index for one diff, so staged, unstaged and untracked changes
  appear once each and renames made outside git are detected. `review
  bundle` reads the patch and writes the rules bundle; `review clean` removes
  the run's artifacts. `references/procedure.md` is the evidence discipline
  the reviewer follows.
- `skills/review-plan/`: the `rule-fairy:review-plan` skill. `review-plan
  paths <plan>` shows which files the plan touches and which tokens were
  dropped; `review-plan bundle <plan> [--paths ...]` writes the plan bundle
  and its `meta.json` under `.rule-fairy/review/plan/`; `review-plan clean`
  removes them. It shares the procedure file with the review skill.
- `agents/rules-reviewer.md`: the read-only reviewer Claude Code runs for
  both skills. Codex plugins have no agents, so there the skills review
  inline.
- `test/`: engine, Markdown, instruction-file, dedup, bundle, git, plan,
  transcript, session-state, changes and plugin-layout suites.
- `reference/`: two rules documenting the hook machinery and the `.mdc`
  format for rule authors.
- `docs/`: the design and contract statement, the how-to for running the
  review from another skill, and the conference talk.

## Checks

```sh
bb test
```

Runs fifteen Babashka suites: engine, Markdown, instruction files, dedup,
bundle, git, plan, transcript, session state, Claude hooks, Codex hooks, the
two skills' scripts and the plugin layout, then the mod's TypeScript tests
through `claude plugin test` where Claude Code is installed. Every suite
builds its rules, documents, and state in temporary directories; the git
and review script suites build real repositories, the latter with a branch,
uncommitted and untracked changes, and a fake `gh` on the PATH.
The Claude hook suite runs `mod.bb` through `hooks/run` against a temporary
project, the way the mod does, and the Codex suite runs the registered
commands from `hooks/codex-hooks.json` through a shell, with a broken
`bb.edn` planted in the project to prove it does not affect the hooks. The
registration test reads `RULE_FAIRY_SETTINGS_FILE` to validate another
hooks manifest. The mod's tests answer its session, process and log calls
from the test, so no Babashka runs there. The skill script suites run each
skill's `scripts/run` shim as a subprocess, the way the skill text tells an
agent to. The layout suite checks that every skill and agent file carries
the frontmatter both harnesses need and that the files the skill text names
exist. `claude plugin validate .` lists the mod's hooks and the calls it
makes. Loading the plugin once with `claude --plugin-dir .` makes Claude
Code write its type declarations under `.claude-plugin/types/`, and
`bunx tsc -p tsconfig.json --noEmit` then type-checks the module and its
tests against them.
