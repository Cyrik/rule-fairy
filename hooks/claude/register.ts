// The Claude Code mod of Rule Fairy: a function hook per event in place of
// the settings hooks, so one delivery is one process and the 10,000-character
// cap on a settings hook's output no longer applies. Each hook hands its
// event to hooks/claude/mod.bb through hooks/run and attaches the context
// entries that prints: after the prompt on prompt.submit, after the tool's
// result on tool.call. With the event goes the head of every Rule Fairy
// frame the conversation already holds, read from the Messages API form of
// the session, so a session that inherited its context, as a fork does,
// starts knowing what is already delivered. A subagent's tool calls deliver
// nothing, as the settings hooks delivered nothing there, and nor does a
// prompt another agent or process composed. A delivery that fails is logged
// to the debug log and attaches nothing, so the event goes on as if
// unhooked.
import type { EngineInterface, Register } from 'claude-code'

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

type Block = { type?: string; text?: string; content?: unknown }

function texts(content: unknown): string[] {
  if (typeof content === 'string') return [content]
  if (!Array.isArray(content)) return []
  return (content as Block[]).flatMap(block =>
    block.type === 'tool_result' ? texts(block.content) : typeof block.text === 'string' ? [block.text] : [],
  )
}

function frameHeads(messages: unknown): string[] {
  if (!Array.isArray(messages)) return []
  return (messages as Block[]).flatMap(message =>
    texts(message.content).flatMap(text => [...text.matchAll(FRAME_HEAD)].map(found => found[1] ?? '')),
  )
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
  if (ran.exitCode !== 0) {
    $.ui.log(`${kind} delivery failed (exit ${ran.exitCode}): ${ran.stderr.trim().slice(-500)}`, {
      to: 'debug',
    })
    return []
  }
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

export const register: Register = on => {
  on('prompt.submit', async ($, e, next) => {
    if (COMPOSED_BY_OTHERS.has(e.origin.kind)) return next(e)
    const context = await deliver($, 'prompt', { prompt: e.text })
    return next(withContext(e, context))
  })

  on('tool.call', { tool: EDIT_TOOL }, async ($, e, next) => {
    const result = await next(e)
    if (e.agentId !== undefined || result.deny !== undefined) return result
    const { tool, tool_use_id, agentId, ...toolInput } = e
    const context = await deliver($, 'edit', { tool_name: tool, tool_input: toolInput })
    return withContext(result, context)
  })

  on('tool.call', { tool: 'Bash' }, async ($, e, next) => {
    const result = await next(e)
    if (e.agentId !== undefined || result.deny !== undefined) return result
    const context = await deliver($, 'shell', {
      tool_name: 'Bash',
      tool_input: { command: e.command },
    })
    return withContext(result, context)
  })
}
