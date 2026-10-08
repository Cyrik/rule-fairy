# Rule Fairy

Repository rules and docs, delivered to your coding agent.

Rule Fairy is a plugin for Claude Code and Codex. It reads the rules a
repository already keeps for Cursor (`.cursor/rules/**/*.mdc`, or any
directories you configure) and injects the relevant ones into the agent's
context: when a prompt mentions a rule's keywords, when the agent edits a file
the rule's globs match, or on the first prompt for rules marked `alwaysApply`.
Rules can import sections of your documentation by heading, and injected rules
come back after the context is compacted. The same matcher powers a review of
a diff or a plan against the rules that apply to it.

Clojure/conj 2026 talk: [Just-in-Time Context for Agents](docs/talks/clojure-conj-2026/presentation.md)
([slides, PDF](docs/talks/clojure-conj-2026/just-in-time-context.pdf)).

## Why not the harnesses' own rules

Claude Code has `.claude/rules/` with `paths:` globs, Codex has `AGENTS.md`,
Cursor has `.cursor/rules/`. Rule Fairy exists for what they leave out:

- **One rule source for three tools.** The same rule files serve Cursor
  natively and Claude Code and Codex through the plugin. The review reads the
  harness instruction files as well, so nothing has to be copied between
  `AGENTS.md`, `CLAUDE.md` and the rules.
- **Prompt keyword triggers.** A rule can fire when the user mentions its
  topics (`promptAnyOf`, `promptRequires`), not only when a file is touched.
- **Firing on edits.** Claude Code's path-scoped rules load when a matching
  file is read, not when it is written or edited (anthropics/claude-code
  #23478, #38487, #95083). Rule Fairy injects before an Edit or Write on
  Claude Code and right after one on Codex.
- **Heading-level documentation imports.** `@doc/guide.md#section` pulls one
  section of a larger document into a rule; overlapping imports merge and
  `<!-- agent-context: omit -->` blocks stay out. Native imports take whole
  files. The rendered rule keeps a line where the import stood, naming the
  reference, because the section itself is delivered in the Required
  documentation blocks, possibly in another shard.
- **Reinjection after compaction.** After `/compact`, Claude Code re-injects
  only the root `CLAUDE.md`, and path-scoped rules return only when a
  matching file is read again. Rule Fairy counts compactions per session and
  delivers matched rules again.
- **Review against the rules.** Two skills check a diff or a plan against
  exactly the rules and instruction files that apply to the paths it touches,
  and report only what a rule supports.

## Install

[Babashka](https://babashka.org) (`bb`) must be on the PATH of the process that
runs your agent. The hooks look in `~/.local/bin`, `/opt/homebrew/bin` and
`/usr/local/bin` as well, for agents launched from an editor or app with a
minimal PATH.

Claude Code, from GitHub or from a local checkout:

```sh
claude plugin marketplace add Cyrik/rule-fairy   # or: claude plugin marketplace add ./rule-fairy
claude plugin install rule-fairy@rule-fairy
```

Codex:

```sh
codex plugin marketplace add Cyrik/rule-fairy
codex plugin add rule-fairy@rule-fairy
```

Running sessions pick up hooks on restart. If the repository still registers
the older project-local hooks in `.claude/settings.json` or `.codex/hooks.json`,
remove them so rules are not injected twice.

The review skills are `/rule-fairy:review` and `/rule-fairy:review-plan` in
Claude Code and `$review` and `$review-plan` in Codex. Both also trigger from
a plain request such as "review this PR against our rules" or "check this
plan against the project conventions". They need `bb`; reviewing a pull
request also needs the GitHub CLI (`gh`), logged in.

## How it works

- **Prompt hook** (`UserPromptSubmit`, both harnesses): selects every
  `alwaysApply` rule plus every rule whose `promptAnyOf` terms appear in the
  prompt (all `promptRequires` terms must appear too), renders them with their
  documentation imports, and injects them.
- **Edit hook** (Claude `PreToolUse`, Codex `PostToolUse`): matches the edited
  path against every rule's `globs` and injects the matches. On Codex the
  injection stops the planned turn so the model considers the rules before
  moving on.
- **Shell hook** (Claude `PostToolUse` and `PostToolUseFailure` on `Bash`,
  Codex `PostToolUse` on `Bash`): a shell command can write files no tool
  input names, such as a heredoc, `sed -i` or `mv`. After the command the hook
  asks git which files changed since the session's last shell check, matches
  them against the globs and injects the rules with the post-edit contract:
  apply them on the next pass, revise the change where it conflicts. The
  check advances with every shell command and starts at the first prompt.
  The check reads the working tree, so a commit, checkout, rebase or pull
  that moves HEAD brings nothing, and a file written and committed in the
  same command is not seen. A change stays pending until its rules have
  reached the agent, so a rule that fails to build or a delivery cut short
  is retried by the next command. On Claude Code, the injected
  context names the changed paths that fit a 1,000-character budget and
  counts the rest, so the prefix never exceeds the frame limit on its own.
  Outside a git checkout nothing is detected. On by default for Claude Code and off for Codex; `:shell-hook`
  in `rule-fairy.edn` switches either (see Configuration).
- **Dedup**: one state file per session records when each rule was injected.
  A rule is injected again after a compaction, or after 2 MiB of transcript
  growth as a fallback for silent context cleanup. A session whose
  transcript already holds Rule Fairy injections it has no record of, as
  after a fork, starts with those rules marked as injected, read from the
  hook records after the last compaction summary; a delivery counts only
  when every one of its shards, told apart by the delivery id in the shard
  header, is recorded. State lives under
  `.rule-fairy/<harness>/` in the project and ignores itself in git.
- **Bounded delivery**: Claude Code truncates hook output over 10,000
  characters (anthropics/claude-code#94358, not configurable), so the Claude
  hooks register 12 lanes per event and split one injection across them.
  A matched set that needs more frames than there are lanes, or more
  documentation than the 64 KiB budget, is delivered in match order as far
  as it fits; the first shard names the rest, which comes with the next
  event that matches it. A single rule too large for either is reported and
  never marked. Codex caps `additionalContext` at 2,500
  tokens by default; the registration sets `additionalContextLimit` to 0.
- **Review skill** (`rule-fairy:review`, both harnesses): fetches a diff (a
  pull request through `gh`, uncommitted work, or a branch against its base),
  builds a bundle of the instruction files, rules and documentation that apply
  to the changed paths with duplicates collapsed, and reviews the diff against
  it. On Claude Code the review runs in the read-only
  `rule-fairy:rules-reviewer` agent; on Codex the skill follows the same
  procedure inline. Findings cite the rule; nothing is edited, committed or
  posted.
- **Plan review skill** (`rule-fairy:review-plan`, both harnesses): the same
  check before any code exists. It works out which files a plan will create
  or edit, from a file-list section or from the paths the text names, builds
  the bundle for those paths, and judges each step against it. Reruns after
  the plan changes.

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
- `hooks/hooks.json`, `hooks/codex-hooks.json`: hook registrations. Every
  command runs `hooks/run`, a small shell wrapper that finds the plugin root,
  hands it to the script as `RULE_FAIRY_PLUGIN_ROOT`, makes sure `bb` is on
  the PATH, and starts Babashka with the plugin's own `bb.edn` so a consuming
  repository's `bb.edn` never reaches the classpath.
- `hooks/claude/`: `common.bb` (project root, session state, lane batching,
  the glob cache, shell-change detection), `prompt.bb`, `edit.bb`,
  `shell.bb`, and `common_test.bb`.
- `hooks/codex/`: `common.bb`, `prompt.bb`, `post_edit.bb` (edits and shell
  commands alike), and `common_test.bb`. The adapters intentionally have
  different delivery contracts.
- `src/rule_fairy/rules.clj`: the engine. Rules source, frontmatter parsing,
  glob and prompt matching, documentation imports with overlap merging,
  rendering, and block splitting. `markdown.clj` holds the line-level Markdown
  structure it shares with the bundle code (fences, headings, sections,
  units); `config.clj` reads `rule-fairy.edn`.
- `src/rule_fairy/instructions.clj`: harness instruction files for the review
  bundle. `AGENTS.md`, `CLAUDE.md` and relatives per touched directory,
  `@path` imports the way Claude Code follows them, block comments stripped,
  one copy per real file. `src/rule_fairy/dedup.clj`: section and paragraph
  deduplication across a bundle, each omission replaced by a line naming the
  kept copy.
- `src/rule_fairy/session_state.clj`: per-session dedup state shared by the
  adapters, including the shell-check marker. `src/rule_fairy/transcript.clj`:
  incremental compaction counting with a scan cursor.
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

## Configuration

Rules are read from `.cursor/rules/**/*.mdc` by default, so a repository that
already uses Cursor rules needs nothing. An optional `rule-fairy.edn` at the
project root adds or moves directories and extensions:

```clojure
{:rules {:dirs [".cursor/rules" "docs/rules"]
         :extensions [".mdc" ".md"]}}
```

Any file under any listed directory with any listed extension is a rule.
Directories are relative to the project root; both keys are optional and
anything else is rejected. Rule names are relative to their own directory, so
the same name in two directories is an error. The frontmatter fields stay
Cursor's (`description`, `globs`, `alwaysApply`) plus the extensions
`promptAnyOf` and `promptRequires`. The format is documented for rule authors
in [reference/mdc.mdc](reference/mdc.mdc).

The same file switches the shell hook per harness. It is on for Claude Code
and off for Codex unless set:

```clojure
{:shell-hook {:claude true :codex true}}
```

Codex makes far more shell calls than edits and rarely edits through the
shell, so the check would cost every call for a rare catch; a repository
where Codex does write through the shell turns it on here. A switched-off
hook still starts, because the plugin's registration is fixed, but exits
before any git call.

Cursor itself is assumed to keep working with these files, but it is not
fully supported at the moment. Cursor ignores the two extension fields and
includes `@path` imports whole rather than by heading; what it does with a
heading import it cannot resolve, and whether saving a rule in its editor
strips unknown fields, has not been checked. If you rely on Cursor, keep your
rules valid for it and treat the extensions as Rule Fairy's.

The review bundle also reads harness instruction files. By default it looks
for `AGENTS.override.md`, `AGENTS.md`, `.claude/AGENTS.md`, `CLAUDE.md`,
`.claude/CLAUDE.md` and `CLAUDE.local.md` in the project root and in every
directory above a changed file, follows their `@path` imports the way Claude
Code does, and includes each real file once. That is the union of what
either harness reads, with one exception: an `AGENTS.md` beside an
`AGENTS.override.md` is left out when a CLAUDE file exists at the root or in
that directory, because Codex reads only the override and Claude Code then
reads only its CLAUDE files. To restrict or reorder them:

```clojure
{:instructions {:files ["AGENTS.md" "CLAUDE.md"]}}
```

An empty vector includes no instruction files. Order matters: when two files
share a section or a long paragraph, the earlier one keeps it and the later
one gets a line naming where the kept copy is.

The hooks write session state and glob caches under `.rule-fairy/claude/` and
`.rule-fairy/codex/` in the project, and review bundles go under
`.rule-fairy/review/<mode>/`. Each of those directories carries a `.gitignore`
ignoring its own content, so nothing needs to be added to the repository's
ignore rules. The hooks also record the directory the plugin runs from in
`.rule-fairy/<harness>/plugin-root`, one absolute path on one line, for a
skill of the project that wants the plugin's review scripts (see Using the
review from another skill). The Claude registration sets `RULE_FAIRY_LANE`
per hook entry.

Context injected by the hooks is labelled `[rule-fairy injected: <rule>]`,
`[rule-fairy matched: alwaysApply ...]`, `[rule-fairy matched: keyword ...]`,
`[rule-fairy matched: glob on <path>]`, `[rule-fairy shard n/m <delivery>]`, and
`[rule-fairy error: ...]`.

## Using the review from another skill

A project's own review skill can run the rules pass with the plugin's
scripts instead of collecting rules itself. The hooks record where the
plugin runs from in `.rule-fairy/<harness>/plugin-root` (`claude` or
`codex`), one absolute path, written on the first prompt of a session. Read
it; a missing file, or one naming a directory that no longer exists, is a
setup error to report, not a reason to look for the plugin elsewhere. Under
that root, `skills/review/scripts/run` is the launcher and
`skills/review/references/procedure.md` the procedure. From the project
root:

```sh
"<root>/skills/review/scripts/run" fetch --base main   # or --local, or --pr <n> [--checkout]
"<root>/skills/review/scripts/run" bundle
"<root>/skills/review/scripts/run" clean                # once every pass has read the artifacts
```

`fetch` writes `.rule-fairy/review/diff/patch.diff` and `meta.json` (the
code revision, the mode and, under `rules`, the rules revision); `bundle`
writes `rules.md` beside them and completes `rules`. A failed command exits
non-zero and leaves no new artifact: report it as a coverage failure rather
than reuse an older bundle. The commands invoke no skill, post nothing and
touch no file of the caller's, with one exception: `--checkout` checks the
pull request out detached and needs the user's explicit authorisation, as
the review skill says. `clean` removes only that directory, when the caller
says so. To review, hand a reader the three files and the procedure
as absolute paths: in Claude Code the `rule-fairy:rules-reviewer` agent with
the prompt from the review skill, elsewhere a subagent that follows the
procedure. It returns findings and coverage to its caller, which builds the
report.

## Checks

```sh
bb test
```

Runs fifteen suites: engine, Markdown, instruction files, dedup, bundle,
git, plan, transcript, session state, Claude hooks, Codex hooks, the two
skills' scripts and the plugin layout. Every suite builds its rules,
documents, and state in temporary directories; the git and review script
suites build real repositories, the latter with a branch, uncommitted and
untracked changes, and a fake `gh` on the PATH.
The hook suites also run the registered commands from `hooks/hooks.json` and
`hooks/codex-hooks.json` through a shell against a temporary project, the way
the harnesses do, with a broken `bb.edn` planted in that project to prove it
does not affect the hooks. The Claude lane test reads
`RULE_FAIRY_SETTINGS_FILE` to validate another registration file. The skill
script suites run each skill's `scripts/run` shim as a subprocess, the way
the skill text tells an agent to. The layout suite checks that every skill
and agent file carries the frontmatter both harnesses need and that the files
the skill text names exist.

## Design decisions

- Keep project rules and their human documentation in the consuming repository.
- Separate installed plugin code, the target project/worktree, and writable
  state. Do not rely on the consumer's Babashka classpath or current directory.
- Share matching and rendering; retain harness-specific input, transcript, and
  delivery handling. Do not unify those behaviours merely for packaging.
  Frontmatter parsing, glob matching, and the rule index live only in the
  engine; adapters keep their cache files, path relativisation, and output.
- An invalid glob in a rule fails the hook with the glob named, instead of
  silently matching nothing. Rule files are user input; the failure is loud on
  purpose.
- `alwaysApply: true` rules are selected by the prompt hooks on every prompt
  and delivered through the normal session dedup, so they arrive with the
  first prompt and return after compaction. Consuming repositories should not
  duplicate that content in `CLAUDE.md` or `AGENTS.md`.
- Transcript metrics are read incrementally. The session state keeps a scan
  cursor and each event counts only the complete lines appended since, so the
  per-prompt cost of the reinjection check no longer grows with the session.
- Each harness keeps one dedup map per session shared by all of its hooks. A
  rule delivered after an edit is not delivered again by the next prompt, and
  the reverse. Codex previously kept separate prompt and post-edit buckets.
- The code, labels, state paths and environment variables say "rule" and
  "rule-fairy"; "MDC" is Cursor's name for the default file format and appears
  only where that format is meant. The rules location is configuration, not
  identity: `.cursor/rules/**/*.mdc` stays the default because Cursor requires
  it and Claude Code's `/init` already recognises it.
- Preserve Claude's distinct lane identities, one-time batch rendering, and
  acknowledgement only after each output is written and flushed. The full
  matched-rule marker need not appear at the end or on every shard.
- Every hook updates its session's state under a per-session file lock.
  Claude Code runs the twelve lanes of one event at once; Codex launches the
  hooks matching an event concurrently and runs a subagent's hooks under the
  parent's session id.
- An edited path is matched the way the project's globs are written: relative
  to the checkout that holds the file, less the project's own position in its
  checkout. A session started in a subdirectory of a repository keeps its
  `src/**` rules working, in that checkout and in a linked worktree of it,
  on both harnesses. Claude Code names that project directory to its hooks;
  Codex does not, so there the project is the nearest directory from the
  session's working directory up to the checkout root that holds a
  `rule-fairy.edn` or `.cursor/rules`, else the checkout root.
  `RULE_FAIRY_PROJECT_DIR` overrides both.
- State files and the glob cache are replaced atomically through a temporary
  file, so a session reading while another writes sees whole content, and a
  hook killed mid-write leaves the old file in place.
- The hooks record where the plugin runs from, in `plugin-root` under the
  harness's state directory, on every run that touches session state, so
  the first prompt of a session writes it. A skill of the consuming project
  reaches the plugin's review scripts and procedure through that file, the
  last root a hook ran from, rather than through the harnesses' plugin
  registries, which differ in shape and in what they point at. A missing
  file, or one naming a directory that no longer exists, is a setup failure
  for that skill to report, never a reason to use another checkout or to
  download code.
- A review's `rules.clean` says whether the guidance the bundle used is what
  is committed. It covers every piece of that guidance, the rules
  directories and their files, `rule-fairy.edn`, the instruction files and
  the documentation they and the rules import, each as written and as the
  file it resolves to, since git reports an edit under a link's target. It
  is false when any of them is modified, untracked or ignored by git, since
  ignored guidance never reached a commit either. Anything that resolves
  outside the checkout, through a configured directory, a link or an
  import, makes it unknown. This note is the canonical statement of that
  contract; `skills/review/references/procedure.md`, the review skill and
  `rules-revision` follow it. One helper answers where a path lies for all
  of the inputs, so a new layout is judged against the contract rather than
  given a case of its own.
- Preserve explicit compaction detection and the 2 MiB transcript-growth
  reinjection fallback. The fallback addresses silent context cleanup as well
  as ordinary compaction; removing it needs separate investigation.
- Keep normal project skills, commands, and unrelated hooks enabled. Installing
  the plugin must not depend on excluding the project settings source.
- Replace the old hook registrations during migration to prevent double firing.
- Register every Codex Rule Fairy hook with `additionalContextLimit: 0`. Codex caps
  `additionalContext` at 2,500 tokens by default and replaces larger output
  with a head-and-tail preview plus a file path, the same silent truncation
  the Claude lanes work around. The Codex adapter renders one unsharded
  bundle, so the cap must be lifted per hook. The engine's 64 KiB documentation
  budget remains the size guard. Codex documents the limit only for
  `additionalContext`; whether the `continue: false` replacement path honours
  it is unstated and needs a live check.

- Review artifacts belong to one run. `fetch` and the plan `bundle` start
  from an empty directory and the skills remove everything once the report
  is out, so a later review cannot pick up an earlier one's inputs.
- Working-tree patches go through a throwaway index loaded from the real
  index's entries but not its stat cache: a staged addition stays in the
  patch even when its path is ignored, and git hashes every file instead of
  trusting a size and mtime that an edit made within the same second as the
  last index write leaves unchanged, and agents edit that fast.

## Known gaps

- **Subagents get no rules.** Claude Code runs the hooks inside subagents
  and marks their input with the agent id; the hooks do nothing there. A
  subagent's context has none of the main thread's rules, and an injection
  recorded there would count against the session and suppress the rule for
  the main thread later. Codex marks nothing on a subagent's tool events and
  gives them the parent's session id, so there a subagent's injections do
  count against the parent. Injecting into subagents with a history of their
  own is a possible follow-up.
- **Shell edits outside git.** The shell hook finds what a command wrote
  through `git status`, so in a directory that is not a checkout files
  written by shell commands get no rules. A walk comparing file times was
  measured and set aside: it beats `git status` only when it skips the
  ignored trees, which takes a faithful reading of every ignore source, and
  outside git it has nothing to skip. It stays a possible optimisation if
  the git check proves too slow somewhere. Inside git, a file another
  session changes between two shell checks is attributed to this session and
  gets its rules once, which is harmless.
- **Lane order.** The twelve Claude Code hook lanes of one event arrive in
  the transcript in arrival order, so a rule split across shards can read out
  of sequence. The shard labels let the model reassemble it.

## License

MIT. See [LICENSE](LICENSE).
