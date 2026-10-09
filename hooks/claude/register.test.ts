// Tests of the mod's own code, run by `claude plugin test`: the world
// beneath the hooks (session, process, log) is answered by the test, so no
// Babashka process runs here. What mod.bb delivers is tested in
// common_test.bb; these cover what the module does around it.
import { expect, test } from 'claude-code/testing'
import type { On, ProcessRunResult } from 'claude-code'
import { failureEntry, frameHeads } from './register'

const DELIVERED = '[rule-fairy shard 1/1 d1]\n[rule-fairy injected: a.mdc]\n\n# a.mdc\n\nbody'

const PROCESS_DELIVERED: ProcessRunResult = {
  exitCode: 0,
  stdout: JSON.stringify({ context: [DELIVERED] }),
  stderr: '',
  isStdoutTruncated: false,
  isStderrTruncated: false,
}

const PROCESS_FAILED: ProcessRunResult = {
  exitCode: 1,
  stdout: '',
  stderr:
    '----- Error --------------------------------------------------------------------\n' +
    'Type:     clojure.lang.ExceptionInfo\n' +
    'Message:  Rule import does not exist: doc/lib.md\n' +
    'Location: common.bb:1:1\n' +
    '----- Stack trace --------------------------------------------------------------\n' +
    'frame\n'.repeat(400),
  isStdoutTruncated: false,
  isStderrTruncated: false,
}

type Run = { argv: readonly string[]; stdin: string | undefined }

// A session the module can read, every process run answered by `answer`;
// the runs made are collected.
function session(on: On, answer: (run: Run) => { value: ProcessRunResult } | { deny: string }): Run[] {
  const runs: Run[] = []
  on('session.id', () => ({ value: 'session-test' }))
  on('session.root', () => ({ value: '/project' }))
  on('session.cwd', () => ({ value: '/project/sub' }))
  on('session.messages', () => ({ value: [] }))
  on('ui.log', () => ({ value: undefined }))
  on('process.run', (_$, e) => {
    const run = { argv: e.argv, stdin: e.init?.stdin }
    runs.push(run)
    return answer(run)
  })
  return runs
}

const prompt = (kind: 'composer' | 'task-notification', text: string) =>
  ({ text, wait: false, origin: { kind } }) as const

test('a delivery whose process fails attaches one error entry to the tool result', async ($, on) => {
  const runs = session(on, () => ({ value: PROCESS_FAILED }))
  on('tool.call', () => ({ result: 'written' }))
  const result = await $.tool.call({ tool: 'Write', file_path: '/project/src/a.clj', content: '(ns a)' })
  expect(result.deny).toBe(undefined)
  expect(result.context?.length).toBe(1)
  const entry = result.context?.[0] ?? ''
  expect(entry.startsWith('[rule-fairy error: edit delivery failed (exit 1)]\n')).toBe(true)
  expect(entry.includes('Message:  Rule import does not exist: doc/lib.md')).toBe(true)
  expect(entry.endsWith('\nNo matched rules were injected or marked as injected.')).toBe(true)
  expect(entry.length < 1_200).toBe(true)
  expect(runs.length).toBe(1)
  expect(runs[0]?.argv[1]).toBe('claude/mod.bb')
})

test('a delivery that succeeds attaches what the process printed, after the tool result', async ($, on) => {
  const runs = session(on, () => ({ value: PROCESS_DELIVERED }))
  on('tool.call', () => ({ result: 'ran' }))
  const result = await $.tool.call({ tool: 'Bash', command: 'printf x > somewhere' })
  expect(result.context).toEqual([DELIVERED])
  const stdin = JSON.parse(runs[0]?.stdin ?? '{}')
  expect(stdin.kind).toBe('shell')
  expect(stdin.input.session_id).toBe('session-test')
  expect(stdin.input.cwd).toBe('/project/sub')
  expect(stdin.input.tool_input).toEqual({ command: 'printf x > somewhere' })
})

test('a hook that throws still attaches an error entry, through its catch handler', async ($, on) => {
  session(on, () => ({ deny: 'the launcher vanished' }))
  on('tool.call', () => ({ result: 'ran' }))
  const result = await $.tool.call({ tool: 'Bash', command: 'true' })
  expect(result.context?.length).toBe(1)
  expect((result.context?.[0] ?? '').startsWith('[rule-fairy error: shell delivery threw]\n')).toBe(true)
})

test('a prompt the person typed is delivered for, after the prompt', async ($, on) => {
  const runs = session(on, () => ({ value: PROCESS_DELIVERED }))
  on('prompt.submit', (_$, e) => ({ text: e.text, context: e.context }))
  const result = await $.prompt.submit(prompt('composer', 'hello there'))
  expect(result.context).toEqual([DELIVERED])
  const stdin = JSON.parse(runs[0]?.stdin ?? '{}')
  expect(stdin.kind).toBe('prompt')
  expect(stdin.input.prompt).toBe('hello there')
})

test('a prompt delivery whose process fails attaches its error entry on the way down', async ($, on) => {
  session(on, () => ({ value: PROCESS_FAILED }))
  on('prompt.submit', (_$, e) => ({ text: e.text, context: e.context }))
  const result = await $.prompt.submit(prompt('composer', 'hello there'))
  expect(result.context?.length).toBe(1)
  expect((result.context?.[0] ?? '').startsWith('[rule-fairy error: prompt delivery failed (exit 1)]\n')).toBe(true)
})

test('a prompt another agent or process composed delivers nothing and runs no process', async ($, on) => {
  const runs = session(on, () => ({ value: PROCESS_DELIVERED }))
  on('prompt.submit', (_$, e) => ({ text: e.text, context: e.context }))
  const result = await $.prompt.submit(prompt('task-notification', 'the subagent reports: done'))
  expect(result.context ?? []).toEqual([])
  expect(runs.length).toBe(0)
})

test('frame heads are read from rendered reminders, marker lines included and bodies left behind', () => {
  const reminder = (event: string, frame: string) =>
    `<system-reminder>\n${event} hook additional context: ${frame}\n</system-reminder>`
  const messages = [
    {
      role: 'user',
      content: [
        {
          type: 'text',
          text: reminder(
            'prompt.submit',
            '[rule-fairy shard 1/1 d1]\n[rule-fairy injected: a.mdc]\n[rule-fairy matched: alwaysApply a.mdc]\n\n# a.mdc\n\nbody',
          ),
        },
      ],
    },
    {
      role: 'user',
      content: [
        {
          type: 'tool_result',
          content: 'edited\n\n' + reminder('tool.call', '[rule-fairy shard 1/2 d2]\n[rule-fairy injected: b.mdc]\n\n# b.mdc'),
        },
        { type: 'text', text: 'quoted outside a reminder: [rule-fairy shard 9/9 x]' },
      ],
    },
    { role: 'assistant', content: 'nothing here' },
  ]
  expect(frameHeads(messages)).toEqual([
    '[rule-fairy shard 1/1 d1]\n[rule-fairy injected: a.mdc]\n[rule-fairy matched: alwaysApply a.mdc]',
    '[rule-fairy shard 1/2 d2]\n[rule-fairy injected: b.mdc]',
  ])
  expect(frameHeads('not a message list')).toEqual([])
})

test('a failure entry names the failure, keeps the head of the detail and says nothing was recorded', () => {
  const entry = failureEntry('shell', 'failed (exit 1)', PROCESS_FAILED.stderr)
  const lines = entry.split('\n')
  expect(lines[0]).toBe('[rule-fairy error: shell delivery failed (exit 1)]')
  expect(lines[1]).toBe('----- Error --------------------------------------------------------------------')
  expect(lines.at(-1)).toBe('No matched rules were injected or marked as injected.')
  expect(entry.length < 1_200).toBe(true)
  expect(failureEntry('prompt', 'timed out', '')).toBe(
    '[rule-fairy error: prompt delivery timed out]\nNo matched rules were injected or marked as injected.',
  )
})
