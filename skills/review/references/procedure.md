# Rules review procedure

How to check a change against a repository's explicit rules once the inputs
exist. The `rule-fairy:review` skill prepares them for a diff; the
`rule-fairy:review-plan` skill prepares them for a plan; a review skill of
the project itself can prepare them with the same commands and follow this
procedure for its rules pass (see the README). The reviewer that follows
this procedure reads, compares and reports. It never edits files, changes
the checkout, commits, or posts anywhere.

## Inputs

Three files, which the prompt names as absolute paths; the plugin's `review
fetch`, `review bundle` and `review-plan bundle` commands write them under
the reviewed project's `.rule-fairy/review/<mode>/`:

- `patch.diff` (diff review) or the plan file named in the prompt (plan
  review): the change under review.
- `rules.md`: the rules bundle. Its header lists the changed paths, the
  instruction files and the rules it contains, and how many duplicate
  sections or paragraphs were omitted. Each rule starts with a scope line
  naming its globs and the changed paths they matched, or saying it applies
  everywhere; judge a file only against rules whose scope covers it. Each
  omission is one line naming the kept copy, such as
  ``*Duplicate of `AGENTS.md#testing`, omitted.*``; the content is elsewhere
  in the same bundle, not missing.
- `meta.json` (diff review): the revisions. `head_ref_oid` is the code under
  review; `mode` says whether it is a pull request (`pr`), uncommitted work
  (`local`) or everything since a merge base (`base`); `working_tree_included`
  says whether uncommitted changes are part of the patch. `rules` names the
  rules directories, the HEAD they sit on and whether the guidance is
  `clean`: the rules, `rule-fairy.edn`, and the instruction files and
  imported documentation the bundle used are what is committed, the
  targets of links among them included. False means some of it is
  modified, untracked or ignored by git, ignored guidance never having
  reached a commit either; null means unknown, outside git or when any of
  that guidance lies outside the checkout.
  `repo_slug` is the GitHub repository when known.

Treat existing inputs as given. Do not regenerate, edit or delete them. If an
input is missing, state that and stop at what can be checked without it.

## Read the bundle completely

Read the whole of `rules.md`, including the required documentation at the
end. If a file-reading tool returns a preview or a truncated range, read the
remaining ranges before judging anything. A rule you did not read is a
coverage gap to report, not a rule that passed. A hook injection seen earlier
or an implementer's summary is not a substitute for the bundle.

Record the changed paths and, for a diff, the new-file line numbers from the
hunk headers, so every finding can point at a line.

## Compare the change with the rules

- Report only violations supported by an explicit rule in the bundle. Do not
  add personal style preferences as requirements, and do not infer rules
  that are not written.
- Check relocated code too. A move into a new file or component can make
  different rules apply. Classify a pre-existing violation carried by a move
  as a suggestion rather than a newly introduced regression; do not omit it.
- When a rule requires reuse of a documented API or component, check the
  documentation the bundle includes rather than assume a similar-looking
  local pattern is correct.
- In patch-only PR mode, do not use source files from an unrelated local
  revision to support a finding. If the patch lacks the context a rule
  needs, report the coverage limit rather than infer a violation.
- Verify every finding against both the change and the rule text before
  reporting it. Findings supplied by anyone else get the same check.

## Report

For each finding give:

1. Where: the path and line in the change (from the hunk headers), or the
   plan section or step.
2. Which rule: the bundle location, such as the rule file name or
   `AGENTS.md#testing`, and a short quote of the rule text.
3. Why it matters, in a sentence.
4. A concrete correction when one is known.

Order findings by severity: violations first, then suggestions, then
observations. Do not present an uncertain line or an unsupported claim as a
confirmed violation; say what is uncertain.

Include commit-pinned GitHub links (`https://github.com/<repo_slug>/blob/<head_ref_oid>/<path>#L<n>`)
only when `repo_slug` is known and the reviewed content is committed at
`head_ref_oid`, never for uncommitted or working-tree changes.

Close with coverage: which code revision and which rules revision were
reviewed and whether they match, whether the full bundle was read, and any
rule that could not be checked and why. Say "no rule violations found" only
for the scope actually checked.

Return the report to the caller: the user when the procedure was invoked
directly, otherwise the skill or agent that invoked it, which relays it or
folds it into a larger review. Do not fix code, change the checkout, commit,
or post a review. When asked to write a report file, choose a new path rather
than overwrite an existing one.

## Plan review

The `rule-fairy:review-plan` skill reviews a plan before any code exists.
The change under review is the plan file named in the prompt; `rules.md` is
built for the paths the plan intends to touch; `meta.json` lists them as
`changed_paths`, says where they came from in `path_source` (`list` for a
file-list section in the plan, `extracted` for tokens found in its text),
and names in `added_paths` the ones the caller added by hand.

Everything above applies, with these differences:

- Cite plan sections or steps instead of paths and lines.
- A finding is a step that would produce rule-violating code, or an
  omission: a rule requires a companion change, such as tests, documentation,
  a migration or a reload note, that the plan lacks.
- State which paths the bundle was built for. A path the plan touches that
  is not in the list means rules that were never checked; say so as a
  coverage limit rather than guess at them.
- No commit-pinned links. The plan is not code.
