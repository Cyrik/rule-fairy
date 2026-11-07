# Rule Fairy

Repository rules and docs, delivered to your coding agent.

Rule Fairy is a plugin for Claude Code and Codex. It reads your project's
Cursor rules and gives the agent the relevant instructions when you mention
a topic or it edits a matching file. It also reviews code changes and plans
against those rules.

- **Keep one set of rules.** Use `.cursor/rules/` with Cursor, Claude Code
  and Codex, or configure another directory.
- **Match the words in your request.** Keyword triggers select relevant
  rules from your prompt, before the agent touches a file.
- **Deliver rules after edits.** Matching rules arrive with an Edit or Write
  result, even if the agent has not read the file first. Enable the shell
  check to cover files changed by shell commands too.
- **Include the documentation a task needs.** Import a whole document or
  one section by heading. Overlapping imports are combined.
- **Bring rules back after compaction.** Matching rules can be delivered
  again when the agent's context is cleared.
- **Review with evidence.** Findings quote the project rule they violate.
  The review also reads applicable `AGENTS.md`, `CLAUDE.md` and related files.

## Install

Install [Babashka](https://babashka.org) (`bb`) and make it available on the
PATH of the process that runs your agent.

### Claude Code

Requires Claude Code 2.1.287 or later.

```sh
claude plugin marketplace add Cyrik/rule-fairy
claude plugin install rule-fairy@rule-fairy
```

Restart the session, or run `/reload-plugins` after installing from the shell.

### Codex

```sh
codex plugin marketplace add Cyrik/rule-fairy
codex plugin add rule-fairy@rule-fairy
```

Restart the session to load the hooks.

<details>
<summary>Local installs, migration and PATH troubleshooting</summary>

For a local Claude Code install, use
`claude plugin marketplace add ./rule-fairy` in the directory containing the
checkout, then run the same install command above.

If the project registers its own rule-injection hooks in
`.claude/settings.json` or `.codex/hooks.json`, remove those registrations
to prevent duplicate injections. Keep unrelated hooks enabled.

For agents launched from an editor or app with a minimal PATH, the hooks
also look for `bb` in `~/.local/bin`, `/opt/homebrew/bin` and `/usr/local/bin`.

</details>

## Try one rule

Existing `.cursor/rules/**/*.mdc` files work without configuration. To try
keyword matching, create `.cursor/rules/testing.mdc` in your project:

```markdown
---
description: Testing guidelines
promptAnyOf: test, testing
globs: test/**
alwaysApply: false
---
# Testing

When adding a regression test, make it fail for the bug it is meant to catch.
```

In a new session, ask:

> Explain how you would test a bug fix in this project. Do not change any files.

Rule Fairy adds the rule to the agent's context with this marker:

```text
[rule-fairy injected: testing.mdc]
```

You can ask the agent which rule it received. Editing a file under `test/`
also selects this rule. Choose a glob that matches your project's test
directory. Use `alwaysApply: true` for instructions that apply to every task.

A rule can include documentation too. If your project has a
`docs/testing.md` file with a `Regression tests` heading, add this line to
the rule body:

```text
@docs/testing.md#regression-tests
```

Only that section and its subsections are included. See
[Writing rules](reference/mdc.mdc) for the full format, keyword conditions
and ways to omit text from imported documents.

## Review changes or a plan

Ask your agent:

> Review my uncommitted changes against our project rules.

Or name a plan file:

> Review docs/plans/search.md against our project rules.

You can also invoke the skills explicitly:

| Task | Claude Code | Codex |
| --- | --- | --- |
| Review code changes | `/rule-fairy:review` | `$review` |
| Review a plan | `/rule-fairy:review-plan` | `$review-plan` |

The code review accepts uncommitted changes, a branch against its base, or
a pull request. Pull request review also requires the
[GitHub CLI](https://cli.github.com) (`gh`), logged in.

Both skills report violations supported by an explicit project rule, with
the rule quoted. They do not replace general bug review or tests. They do
not edit your code or plan, commit, or post findings to GitHub.

To include this check in your own review skill, see
[Using the review from another skill](docs/review-from-a-skill.md).

## Optional configuration

No configuration file is needed for existing Cursor rules. Put overrides
in `rule-fairy.edn` at the project root.

To read rules from another directory as well:

```clojure
{:rules {:dirs [".cursor/rules" "docs/rules"]}}
```

Shell commands can edit files without an Edit or Write tool call. Rule
Fairy can check for those changes after each command and deliver the
matching rules for the agent's next pass. This check is on by default for
Claude Code and off for Codex. To enable it for both:

```clojure
{:shell-hook {:claude true :codex true}}
```

Combine these entries in one map if you use both. The
[configuration reference](docs/configuration.md) covers rule extensions,
ignored prompt blocks, instruction-file selection and state files.

## Limits to know

- **Claude Code must be able to load the plugin's module.** Older versions
  and an organisation's `allowManagedModsOnly` policy can prevent rule
  injection while leaving the review skills available. Rule Fairy 0.2.3
  uses settings hooks for older Claude Code versions.
- **Shell detection needs Git.** Outside a Git checkout, use Edit or Write
  for file changes to select rules. Inside a checkout, a file written and
  committed in the same shell command is not detected.
- **Rules arrive after edits.** The agent must apply them on its next pass
  and revise the change where it conflicts. They do not prevent the initial
  edit.

One delivery carries up to 64 KiB of imported documentation. Larger sets of
rules are delivered over matching events; a single rule that cannot fit is
reported. A failed delivery is logged, and reported to the model where the
event still allows it; the prompt or tool goes on.

[Design and delivery behavior](docs/design.md) explains delivery timing,
compaction, subagents, failure handling and the remaining limits. Cursor
reads the same rule files, but does not support Rule Fairy's keyword fields
or section imports in the same way; see the compatibility note there.

## Further reading

- [Writing rules](reference/mdc.mdc)
- [Configuration](docs/configuration.md)
- [Design and delivery behavior](docs/design.md), the canonical contracts
- [Using the review from another skill](docs/review-from-a-skill.md)
- [Contributing](CONTRIBUTING.md), including repository layout and checks
- [Hook reference](reference/agent_hooks.mdc), for work on the plugin
- [Just-in-Time Context for Agents](docs/talks/clojure-conj-2026/presentation.md),
  the Clojure/conj 2026 talk ([PDF slides](docs/talks/clojure-conj-2026/just-in-time-context.pdf))

## License

MIT. See [LICENSE](LICENSE).
