# Agents

This file applies to every agent that writes in this repository.

## Technical Writing Style: ASD-STE100

Write technical documentation in the style of ASD-STE100.

Applies to prose you write, not code: markdown files, `.clj` comments (`;`, `;;`), docstrings, error messages (exceptions, validation messages, UI-facing error text),
and external API text (response bodies, webhook payloads, API docs).

Does not constrain identifiers, function names, or code structure - only the natural-language text.

### Comments and docstrings: when to write one

Default to none. Code that needs a comment to be understood is usually code to rename or restructure instead.

Write one only when the code cannot state the thing itself:

- Function docstrings describing how a function is intended to be called and used.
- A constraint from outside this file: an ordering requirement, a third-party API quirk, a regulatory rule.
- A warning about a real footgun.
- A link to an external source: an issue, a spec, a standard.

Delete comments that restate the code.

```clojure
;; Bad - restates the code
;; Fetch the manufacturer by id
(defn manufacturer-by-id [ctx id] ...)

;; Good - states a constraint the code can't show
;; Commit before fetch; the entity isn't in `db` until the tx lands.
```

Do not restate the conventions that every function of its kind follows. A reader who knows this repository expects them.

A wrapper does not repeat the docstring of the function it calls.

When code applies a convention in an unusual way, write the reason as a comment at that line.

### Write for a reader with no context

The reader has this file open and nothing else: no pull request, no issue, no conversation with you. Write only what still makes sense to that reader.

Never refer to the conversation, prompt or task that produced the code. Do not use `this change`, `the request`, `as discussed`, or `per the requirement`.

State the constraint, not your decision. A constraint stays true when someone moves the code; a decision does not.

```clojure
;; Bad - only makes sense to someone who was in the room
;; We pulled this out into a var so the handler stays hot-reloadable, as discussed.

;; Good - the fact that forced it
;; Routes must point at #'handler vars; a plain fn value can't be redefined from the REPL.
```

### No change history in comments

Comments describe the code as it is, in the present tense. What the code did before, and what you removed, moved, renamed or replaced, goes in the commit message and the pull request description.

Do not use `previously`, `no longer`, `used to`, `formerly`, `was moved`, `has been removed`, `renamed from`, or `instead of` to refer to an earlier version of the code.

```clojure
;; Bad - archaeology; the reader can't see the version you're comparing to
;; Validation no longer happens here, it moved to the service layer.
;; Renamed from `fetch-mfr`; the old one took a db instead of a ctx.

;; Good - delete both. The commit message carries this.
```

### Where this doesn't apply

- Identifiers, symbols, keywords, and code itself are unaffected.
- `(comment ...)` rich comment forms hold code, not prose.
