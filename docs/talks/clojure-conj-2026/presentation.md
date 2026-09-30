# Just-in-Time Context for Agents

Lukas Domagala · Clojure/conj 2026

[Slides (PDF)](just-in-time-context.pdf)

Text accompanying a 10-minute talk about making project knowledge available
to coding agents during their work. Rule Fairy is one example of the approach.
The form change below is an illustrative task based on Scarlet's project
rules, not a recorded agent run.

## A capable programmer

Imagine a new programmer joins your team. They know Clojure, they're
comfortable at the REPL, and they can write code. But they don't know your
project yet.

They don't know which components already exist. They don't know why you
put authorization checks in a particular place. They don't know which
test helpers everyone uses, or the setup step that will waste an afternoon
if they miss it.

Now imagine their knowledge of the project resets every morning. That's
the situation with coding agents, and it's the one I want to think about.

How would you onboard that person? You wouldn't hand them all the
documentation and tell them to spend a week reading it. You'd give them
some orientation and a task. The task gives the documentation a purpose.
When I'm changing a form, I have a reason to learn how forms work here.

But someone new doesn't know what to search for. They don't know we
already have a component for this. They find an old example and copy a
pattern the team has moved away from.

An experienced colleague fixes that. They say, "You're working on that?
Have a look at this guide. Also, there's an existing helper you can use."

That's what I mean by just-in-time context. The documentation already
exists. What's missing is the connection between the current task and the
part of that documentation that matters now.

## Add a field to a form

Here's a small example from Scarlet, my work project. Say the task is to
add an optional field to an existing form.

| During the task | Useful project knowledge |
| --- | --- |
| Change the form in `*_view.clj` | Existing controls and form conventions |
| Accept the value in `*_controller.clj` | Coercion schemas and route reloads |
| Write a test in `*_test.clj` | Test setup, assertions, and how to run it |

First, the agent works on the form. It can write an input, but our project
has form controls and conventions already. There's a particular way to
connect the control to the form model, and a component catalog that helps
it find the right control and tells it where to look for details.

Then it changes the controller so the submitted value is accepted.
Different knowledge matters here. We have conventions for coercion
schemas, and we have one practical gotcha.

As the agent works on the controller, the matching rule brings this
guidance into its context:

> **Reloading Routes After a Schema Change**
>
> Reitit compiles routes at startup, so a coercion schema change doesn't
> affect the running router until routes are recompiled. `:reload` updates
> the var but not the compiled router — the symptom is silently dropped
> form fields, no error, and REPL tests still pass because they bypass
> routing.

Nothing about knowing Clojure tells you that. It's project knowledge, and
it becomes available as the agent works on the part of the project where
it matters.

Then it writes a test. Knowing `clojure.test` gets it started. Now it needs
our scenario setup helpers, how we use matcher-combinators, and the command
for running the test.

That is one task, with different bits of project knowledge becoming
useful as the work moves through the code.

I extracted the machinery for this from Scarlet into a tool called Rule
Fairy. It's a plugin for Claude Code and Codex, and the hooks are Babashka
scripts, so they start fast enough to run on every prompt and every edit.
It uses rules to connect things mentioned in a prompt, or files being
edited, to the relevant guidance, and adds that guidance to the agent's
context as it works.

This is a simplified header from that same controller rule,
`.cursor/rules/controller_patterns.mdc`:

```text
---
description: Controller conventions for this project
globs: apps/**/ui/**/*_controller.clj
alwaysApply: false
---
@doc/conventions/controller_conventions.md#route-coercion-schemas
```

The file pattern says where it applies. The paragraph we just saw is part
of the rule's body. The `@` line imports one section of the coercion
conventions.

Rules can bring in sections of existing documentation. I can keep the
explanation in the document a colleague would read, and use the rule to
say when an agent should receive it.

The selection here is quite simple. A test file is a useful clue that
testing conventions matter. A controller file is a useful clue that the
controller conventions matter. That's enough to make some of those
connections explicit.

## Make project knowledge available

The part I care about is making project knowledge available during the
work.

Some of that knowledge is conventions, like the given/when/then structure
of our tests. Some is a map of what already exists, like the component
catalog. Some is the business entities and how they relate, like how an
evaluation report is modelled. And some is a warning about something
awkward in our setup, like the route reload.

For any of those, I need to write down what matters and say when it
matters. The explanation can live with the project and change alongside
the code. The connection can be as simple as a file pattern or a term
that appears in a task.

The alternative is putting everything in the startup instructions.
Scarlet's CLAUDE.md is three and a half kilobytes. The rules and the
documents they draw on are about two hundred and twenty kilobytes as whole
files, and the rules select individual sections from them. I don't want
all of that competing for attention during every task. Most of it doesn't
matter for the task at hand.

There's still a tradeoff in how much to deliver. A rule for UI files can
bring in guidance about forms even when the particular change doesn't
involve a form. The match is approximate. I still need to decide whether
the context is useful enough to include, and keep the documentation
current.

Also, receiving the guidance doesn't guarantee the agent will follow it.
We still need tests and review. I want the expectations available so
the agent can use them, and so feedback can point to something explicit.

This is a way to make the onboarding repeatable. The rules and docs give
the next session a starting point that we can inspect and improve.

## The next decision

Back to the colleague who starts fresh every morning.

I'd give them enough orientation to begin, a concrete task, and a way to
reach the relevant project knowledge while they work. I'd also want the
things we explain along the way to be easier to find tomorrow.

[Rule Fairy](https://github.com/Cyrik/rule-fairy) is the version of this
I've built for my workflow. It reads the rules a repository already keeps
for Cursor, so if you have those, you have a starting point.

Here's what I'd take back to your own project. Pick one task where you
keep supplying the same missing explanation. Write it down, then think
about what would make it show up at the useful moment.

What does the agent need to know for the decision it's about to make?
