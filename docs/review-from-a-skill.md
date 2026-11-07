# Using the review from another skill

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

The `rules.clean` field of `meta.json` says whether the guidance the bundle
used is what is committed; the note on it in [design.md](design.md) is the
canonical statement.
