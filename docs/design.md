# Rule Fairy design

This file is the canonical statement of what the hooks and the review
guarantee. The [README](../README.md) summarises it; the code, the skills,
the tests and [reference/agent_hooks.mdc](../reference/agent_hooks.mdc)
follow it.

## Delivery

- **Prompt hook** (Claude Code `prompt.submit`, Codex `UserPromptSubmit`):
  selects every `alwaysApply` rule plus every rule whose `promptAnyOf` terms
  appear in the prompt (all `promptRequires` terms must appear too), renders
  them with their documentation imports, and injects them with the prompt.
  An import is rendered as a line naming the reference where it stood; the
  section itself is delivered in the Required documentation blocks, possibly
  in another shard. On Claude Code a prompt the person did not write, such
  as a background task's report, another session's message or another
  plugin's prompt, selects nothing: keyword rules match what the person
  asked. For the same reason text inside a `<system>` block is not matched
  on either harness: an orchestrator that sends its instructions as the
  prompt marks them that way. The block names are configurable
  (`:prompt {:ignored-blocks [...]}` in `rule-fairy.edn`).
- **Edit hook** (Claude Code `tool.call` on Edit, Write, MultiEdit,
  NotebookEdit and MCP edit tools, Codex `PostToolUse`): matches the edited
  path against every rule's `globs` and injects the matches, with every
  `alwaysApply` rule the context lacks. On Claude Code the rules arrive with
  the tool's result; on Codex the injection stops the planned turn so the
  model considers the rules before moving on.
- **Shell hook** (Claude Code `tool.call` on `Bash`, Codex `PostToolUse` on
  `Bash`): a shell command can write files no tool input names, such as a
  heredoc, `sed -i` or `mv`. After the command the hook asks git which files
  changed since the session's last shell check, matches them against the
  globs and injects the rules, with every `alwaysApply` rule the context
  lacks, under the post-edit contract: apply them on the next pass, revise
  the change where it conflicts. The check advances with every shell command
  and starts at the first prompt. The check reads the working tree, so a
  commit, checkout, rebase or pull that moves HEAD brings nothing, and a
  file written and committed in the same command is not seen. A change
  stays pending until its rules have reached the agent, so a rule that
  fails to build or a delivery cut short is retried by the next command.
  The injected context names the changed paths that fit a 1,000-character
  budget and counts the rest, so a rebase's hundreds of paths stay one line.
  Outside a git checkout nothing is detected. On by default for Claude Code
  and off for Codex; `:shell-hook` in `rule-fairy.edn` switches either. A
  switched-off hook still starts, because the plugin's registration is
  fixed, but exits before any git call.
- **Dedup**: on Claude Code a rule is delivered when no whole delivery of it
  is in the conversation the model holds, read at each event, and none was
  delivered in the last minute, which covers tool calls that run side by
  side. A compaction empties the conversation of past deliveries, so the
  next matching event delivers again; a forked session starts with what it
  inherited; no transcript file is read. A delivery counts only when every
  one of its entries, told apart by the delivery id in the shard header, is
  present. On Codex one state file per session records the transcript
  metrics at each injection, and a rule is injected again after a
  compaction or after 2 MiB of transcript growth as a fallback for silent
  context cleanup. State lives under `.rule-fairy/<harness>/` in the project
  and ignores itself in git.
- **Bounded delivery**: a matched set is delivered in match order as far as
  one delivery carries it. The delivery opens by naming the rest, which
  comes with the next event that matches it, and a single rule too large
  for one delivery is reported and never recorded. One delivery carries
  documentation within the 64 KiB budget on both harnesses, and on Claude
  Code at most two context entries of 100,000 characters each, which the
  model reads whole; a settings hook's output is cut at 10,000 characters
  (anthropics/claude-code#94358), which is why the Claude Code side is a mod.
  Codex caps `additionalContext` at 2,500 tokens by default; the
  registration sets `additionalContextLimit` to 0, and the post-edit hook's
  `continue: false` path delivered 58 KB whole on Codex CLI 0.163.
- **Failures are visible**: on Claude Code a delivery that fails, its
  process exiting non-zero or the hook itself throwing or timing out,
  attaches one error entry naming the failure and logs a line to the
  transcript, so the model and the person both see it, and the prompt or
  tool goes on as if unhooked. Once a prompt has entered the session no
  context can attach any more, so a failure after that point is logged
  only. On Codex a rule that fails to build fails the hook, which Codex
  reports, and a shell change stays pending for the next command.
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

## Decisions

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
- `alwaysApply: true` rules are selected by every hook, prompt, edit and
  shell, and delivered through the normal session dedup, so they arrive with
  a session's first event and return with the next event of any kind after
  a compaction. Consuming repositories should not duplicate that content in
  `CLAUDE.md` or `AGENTS.md`.
- On Codex, transcript metrics are read incrementally. The session state
  keeps a scan cursor and each event counts only the complete lines appended
  since, so the per-prompt cost of the reinjection check does not grow with
  the session. Claude Code reads no transcript: the mod reads the
  conversation the model holds, which is the question the metrics
  approximated.
- Each harness keeps one dedup map per session shared by all of its hooks. A
  rule delivered after an edit is not delivered again by the next prompt, and
  the reverse.
- Each agent of a session has its own deliveries, on both harnesses. On
  Claude Code a subagent's tool calls are matched against the subagent's own
  conversation and recorded under its own state file, `<session>.<agent>.edn`,
  so a fork starts with what it inherited, a fresh subagent starts empty,
  and neither touches the main thread's history or a sibling's. A subagent's
  task is matched at spawn the way a prompt is, always-on and keyword rules,
  and what it selects is appended to the task inside the reminder wrapper
  hook context uses, since a spawn has no context channel; a fork is matched
  against what it inherits. On Codex, whose tool events carry the agent id
  since CLI 0.163, the same key holds a subagent's delivery record and its
  transcript metrics, read from the subagent's own rollout, which its events
  name; a subagent's task is not matched, since Codex sends no prompt event
  for it, so its first delivery comes with its first edit or shell command.
  On both harnesses a subagent's first shell command starts its own shell
  check, as a session's first prompt does.
- The code, labels, state paths and environment variables say "rule" and
  "rule-fairy"; "MDC" is Cursor's name for the default file format and appears
  only where that format is meant. The rules location is configuration, not
  identity: `.cursor/rules/**/*.mdc` stays the default because Cursor requires
  it and Claude Code's `/init` already recognises it.
- The Claude Code adapter is a mod: one in-process hook per event, one
  Babashka process per delivery, context entries of up to 100,000
  characters. A settings hook's output is cut at 10,000 characters
  (anthropics/claude-code#94358), too little for a rule with its
  documentation, and splitting a delivery across several hooks to stay under
  the cap needs a batch protocol whose failure modes outnumber the problem.
  The Codex adapter keeps its settings hooks, whose cap is configurable.
- Every hook updates its session's state under a per-session file lock.
  Claude Code runs tool calls, and so their hooks, side by side; Codex
  launches the hooks matching an event concurrently and runs a subagent's
  hooks under the parent's session id with the agent id on its tool events.
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
  download code. [review-from-a-skill.md](review-from-a-skill.md) is the
  how-to.
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
- On Codex, compaction is detected explicitly and a rule is injected again
  after 2 MiB of transcript growth, a fallback for silent context cleanup
  as well as ordinary compaction. Claude Code has neither: the conversation
  read says exactly what the model holds, and a growth fallback there
  re-sends rules that are still in context.
- Keep normal project skills, commands, and unrelated hooks enabled. Installing
  the plugin must not depend on excluding the project settings source.
- Register every Codex Rule Fairy hook with `additionalContextLimit: 0`. Codex
  caps `additionalContext` at 2,500 tokens by default and replaces larger
  output with a head-and-tail preview plus a file path, the same silent
  truncation Claude Code applies to a settings hook's output. The Codex
  adapter renders one unsharded bundle, so the cap must be lifted per hook.
  The engine's 64 KiB documentation budget remains the size guard. Codex
  documents the limit only for `additionalContext`; the post-edit hook's
  `continue: false` path is not capped either, by a live check on CLI 0.163
  that delivered 58 KB whole.
- Review artifacts belong to one run. `fetch` and the plan `bundle` start
  from an empty directory and the skills remove everything once the report
  is out, so a later review cannot pick up an earlier one's inputs.
- Working-tree patches go through a throwaway index loaded from the real
  index's entries but not its stat cache: a staged addition stays in the
  patch even when its path is ignored, and git hashes every file instead of
  trusting a size and mtime that an edit made within the same second as the
  last index write leaves unchanged, and agents edit that fast.

## Limits

- **Shell edits outside git.** The shell hook finds what a command wrote
  through `git status`, so in a directory that is not a checkout files
  written by shell commands get no rules. A walk comparing file times was
  measured and set aside: it beats `git status` only when it skips the
  ignored trees, which takes a faithful reading of every ignore source, and
  outside git it has nothing to skip. It stays a possible optimisation if
  the git check proves too slow somewhere. Inside git, a file another
  session changes between two shell checks is attributed to this session and
  gets its rules once, which is harmless.
- **Where the mod cannot load, nothing is injected on Claude Code.** The
  module needs Claude Code 2.1.287; on an older version it does not load
  while the review skills work. `disableAllHooks` stops the module and
  every settings hook and leaves the skills. `--safe-mode` disables the
  whole plugin, skills included. An organisation's `allowManagedModsOnly`
  policy stops the module alone: settings hooks keep running, the skills
  load. `claude plugin test`, run from a directory without a mod, says
  whether mods can load at all but does not report that policy; its
  refusal shows in the debug log.
- **The conversation read is bounded.** The mod reads the newest 4,096
  entries of the conversation, so a rule delivered further back than that,
  in a session that never compacted, is delivered again.
- **A Codex subagent's task is not matched.** Codex sends no prompt event
  for the task a parent gives a subagent, so the subagent's first delivery
  comes with its first edit or shell command. Claude Code matches the task
  at spawn.
- **Cursor is not fully supported.** Cursor ignores `promptAnyOf` and
  `promptRequires` and includes `@path` imports whole rather than by
  heading. What it does with a heading import it cannot resolve, and
  whether saving a rule in its editor strips unknown fields, has not been
  checked.
