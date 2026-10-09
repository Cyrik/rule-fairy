// The Claude Code mod of Rule Fairy: a function hook per event in place of
// the settings hooks, so one delivery is one process and the 10,000-character
// cap on a settings hook's output no longer applies. Each hook hands its
// event to hooks/claude/mod.bb through hooks/run and attaches the context
// entries that prints: after the prompt on prompt.submit, with the tool's
// result on tool.call. With the event goes the head of every Rule Fairy
// frame the conversation already holds, read from the Messages API form of
// the session, so a session that inherited its context, as a fork does,
// starts knowing what is already delivered. A subagent's tool calls deliver
// nothing, as the settings hooks delivered nothing there, and nor does a
// prompt another agent or process composed. A delivery that fails, its
// process exiting non-zero or the hook itself throwing or timing out,
// attaches one error entry naming the failure and logs a line to the
// transcript, so the model and the person both see it, and the prompt or
// tool goes on as if unhooked. Once a prompt has entered the session no
// context can attach any more, so a failure after that point is logged only.
import type { EngineInterface, HookFailure, Register } from 'claude-code'

type Kind = 'prompt' | 'edit' | 'shell'

const EDIT_TOOL = /^(Edit|Write|MultiEdit|NotebookEdit)$|^mcp__.*(edit|write|patch)/

// Prompts the person did not write: a background task's report, another
// session's message, a coordinator's hand-off, an observer's note or a
// plugin's own prompt. Keyword rules match what the person asked, so these
// deliver nothing. The settings hook had no way to tell them apart and
// matched rules against a subagent's report.
const COMPOSED_BY_OTHERS = new Set([
  'task-notification',
  'peer',
  'peer-send-message',
  'projects-relay',
  'coordinator',
  'observer',
  'observer-activity',
  'plugin',
])

// A frame as the model reads it: Claude Code wraps a hook's context in a
// system reminder naming the event, and the frame's shard header and marker
// lines follow. Only those lines are captured; the rule bodies stay behind.
const FRAME_HEAD = /<system-reminder>\n[\w.:]+ hook additional context: (\[rule-fairy shard [^\n]*(?:\n\[rule-fairy [a-z]+: [^\n]*)*)/g

// How much of a failed process's error output the entry carries: Babashka
// prints the error's type, message and location first and the stack last.
const STDERR_HEAD_CHARS = 1_000

type Block = { type?: string; text?: string; content?: unknown }

function texts(content: unknown): string[] {
  if (typeof content === 'string') return [content]
  if (!Array.isArray(content)) return []
  return (content as Block[]).flatMap(block =>
    block.type === 'tool_result' ? texts(block.content) : typeof block.text === 'string' ? [block.text] : [],
  )
}

export function frameHeads(messages: unknown): string[] {
  if (!Array.isArray(messages)) return []
  return (messages as Block[]).flatMap(message =>
    texts(message.content).flatMap(text => [...text.matchAll(FRAME_HEAD)].map(found => found[1] ?? '')),
  )
}

// The one context entry a failed delivery attaches: the failure named the
// way an oversized rule's entry names its reason, the head of the detail,
// and the line that tells the model nothing was recorded.
export function failureEntry(kind: Kind, failure: string, detail: string): string {
  const head = detail.trim().slice(0, STDERR_HEAD_CHARS)
  return [
    `[rule-fairy error: ${kind} delivery ${failure}]`,
    ...(head === '' ? [] : [head]),
    'No matched rules were injected or marked as injected.',
  ].join('\n')
}

function reportFailure($: EngineInterface, kind: Kind, failure: string, detail: string): string {
  $.ui.log(`rule-fairy: ${kind} delivery ${failure}`, { to: 'transcript' })
  return failureEntry(kind, failure, detail)
}

function hookFailure(error: HookFailure): string {
  return error.kind === 'timeout' ? 'timed out' : 'threw'
}

async function deliver(
  $: EngineInterface,
  kind: Kind,
  input: Record<string, unknown>,
): Promise<readonly string[]> {
  const root = $.plugin.root
  const [sessionId, projectDir, cwd, messages] = await Promise.all([
    $.session.id(),
    $.session.root(),
    $.session.cwd(),
    $.session.messages({ as: 'api' }),
  ])
  const ran = await $.process.run([`${root}/hooks/run`, 'claude/mod.bb'], {
    cwd,
    env: { CLAUDE_PLUGIN_ROOT: root, CLAUDE_PROJECT_DIR: projectDir },
    stdin: JSON.stringify({
      kind,
      input: { session_id: sessionId, cwd, in_context_frames: frameHeads(messages), ...input },
    }),
    timeoutMs: 60_000,
  })
  if (ran.exitCode !== 0) return [reportFailure($, kind, `failed (exit ${ran.exitCode})`, ran.stderr)]
  const printed: { context?: string[] } = JSON.parse(ran.stdout)
  return printed.context ?? []
}

function withContext<T extends { context?: readonly string[] }>(
  carrier: T,
  context: readonly string[],
): T {
  return context.length === 0
    ? carrier
    : { ...carrier, context: [...(carrier.context ?? []), ...context] }
}

// The tool's result with the hook failure's entry attached; a denied call
// stays as it is.
function withFailure<R extends { deny?: string; context?: readonly string[] }>(
  $: EngineInterface,
  kind: Kind,
  result: R,
  error: HookFailure,
): R {
  const entry = reportFailure($, kind, hookFailure(error), error.message ?? '')
  return result.deny !== undefined ? result : withContext(result, [entry])
}

export const register: Register = on => {
  on('prompt.submit', async ($, e, next) => {
    if (COMPOSED_BY_OTHERS.has(e.origin.kind)) return next(e)
    const context = await deliver($, 'prompt', { prompt: e.text })
    return next(withContext(e, context))
  }).catch(($, e, next) => {
    const entry = reportFailure($, 'prompt', hookFailure(next.error), next.error.message ?? '')
    return next.called ? next(e) : next(withContext(e, [entry]))
  })

  on('tool.call', { tool: EDIT_TOOL }, async ($, e, next) => {
    const result = await next(e)
    if (e.agentId !== undefined || result.deny !== undefined) return result
    const { tool, tool_use_id, agentId, ...toolInput } = e
    const context = await deliver($, 'edit', { tool_name: tool, tool_input: toolInput })
    return withContext(result, context)
  }).catch(async ($, e, next) => withFailure($, 'edit', await next(e), next.error))

  on('tool.call', { tool: 'Bash' }, async ($, e, next) => {
    const result = await next(e)
    if (e.agentId !== undefined || result.deny !== undefined) return result
    const context = await deliver($, 'shell', {
      tool_name: 'Bash',
      tool_input: { command: e.command },
    })
    return withContext(result, context)
  }).catch(async ($, e, next) => withFailure($, 'shell', await next(e), next.error))
}
