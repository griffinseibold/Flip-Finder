import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';

import { streamChat, type Account, type ChatEvent, type ChatMessage } from '../lib/api';
import { formatCompact } from '../lib/format';
import { Markdown } from '../lib/markdown';
import { FlipLookup } from './FlipLookup';
import { HistoryLookup } from './HistoryLookup';

type Lookup = Extract<ChatEvent, { type: 'flips' | 'history' }>;

interface Turn {
  role: 'user' | 'assistant';
  content: string;
  lookups: Lookup[];
  /** What the assistant is doing before its answer starts. */
  status: string | null;
  /** Shown while the answer waits for the language model to start; cleared once it is working. */
  waiting: string | null;
  error: string | null;
  pending: boolean;
}

const SUGGESTIONS = ['What should I flip right now?', 'What could I make with 50M?', 'Is dragon bones a good flip?'];
const HISTORY_SUGGESTION = 'Is dragon bones trading more than usual?';

/** A conversation with the homelab's language model about flips for the selected account. */
export function Chat({ account, history }: { account: Account | null; history: boolean }) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState('');
  const controller = useRef<AbortController | null>(null);
  const log = useRef<HTMLDivElement>(null);
  const busy = turns.at(-1)?.pending ?? false;
  const suggestions = [
    ...SUGGESTIONS,
    ...(history ? [HISTORY_SUGGESTION] : []),
    ...(account?.buyLimits.length ? ['Which of my buy limits reset soonest?'] : []),
  ];

  // Follow the answer as it streams, scrolling only the conversation, not the page.
  useEffect(() => {
    if (log.current) {
      log.current.scrollTop = log.current.scrollHeight;
    }
  }, [turns]);

  useEffect(() => () => controller.current?.abort(), []);

  const updateAnswer = (change: (turn: Turn) => Turn) =>
    setTurns((current) => {
      const last = current.at(-1);
      return last ? [...current.slice(0, -1), change(last)] : current;
    });

  const send = async (text: string) => {
    const question = text.trim();
    if (!question || busy) {
      return;
    }
    const history: ChatMessage[] = [
      ...turns.filter((turn) => turn.content && !turn.error).map(({ role, content }) => ({ role, content })),
      { role: 'user', content: question },
    ];
    setTurns((current) => [
      ...current,
      { role: 'user', content: question, lookups: [], status: null, waiting: null, error: null, pending: false },
      { role: 'assistant', content: '', lookups: [], status: 'Thinking…', waiting: null, error: null, pending: true },
    ]);
    setDraft('');

    const request = new AbortController();
    controller.current = request;
    try {
      await streamChat(account?.accountHash ?? null, history, (event) => {
        switch (event.type) {
          case 'status':
            updateAnswer((turn) => ({ ...turn, waiting: event.message }));
            break;
          case 'tool':
            updateAnswer((turn) => ({ ...turn, waiting: null, status: lookupStatus(event) }));
            break;
          case 'flips':
          case 'history':
            updateAnswer((turn) => ({ ...turn, lookups: [...turn.lookups, event] }));
            break;
          case 'text':
            updateAnswer((turn) => ({ ...turn, status: null, waiting: null, content: turn.content + event.text }));
            break;
          case 'error':
            // The error says why the wait ended, so the note about waiting goes.
            updateAnswer((turn) => ({ ...turn, waiting: null, error: event.message }));
            break;
          case 'done':
            break;
        }
      }, request.signal);
    } catch {
      if (!request.signal.aborted) {
        updateAnswer((turn) => ({ ...turn, error: 'Could not reach Flip Finder.' }));
      }
    } finally {
      updateAnswer((turn) => ({ ...turn, status: null, waiting: null, pending: false }));
    }
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    void send(draft);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void send(draft);
    }
  };

  const startOver = () => {
    controller.current?.abort();
    setTurns([]);
  };

  return (
    <section className="chat" aria-label="Chat">
      <div className="chat-log" role="log" aria-busy={busy} ref={log}>
        {turns.length === 0 && (
          <div className="chat-welcome">
            <h2>Ask about flips</h2>
            <p className="muted">
              Answers use live Grand Exchange prices
              {account ? ', your coins, membership and buy limits.' : '. Connect RuneLite to include your account.'}
            </p>
            <div className="suggestions">
              {suggestions.map((suggestion) => (
                <button key={suggestion} type="button" className="suggestion" onClick={() => void send(suggestion)}>
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        )}
        {turns.map((turn, index) =>
          turn.role === 'user' ? (
            <div key={index} className="turn turn-user">
              <p>{turn.content}</p>
            </div>
          ) : (
            <div key={index} className="turn turn-assistant">
              {turn.content && <Markdown text={turn.content} />}
              {turn.status && <p className="chat-status">{turn.status}</p>}
              {turn.waiting && <p className="chat-waiting">{turn.waiting}</p>}
              {turn.error && (
                <p className="chat-error" role="alert">
                  {turn.error}
                </p>
              )}
              {turn.lookups.map((lookup, lookupIndex) =>
                lookup.type === 'flips' ? (
                  <FlipLookup key={lookupIndex} lookup={lookup} />
                ) : (
                  <HistoryLookup key={lookupIndex} lookup={lookup} />
                ),
              )}
            </div>
          ),
        )}
      </div>

      <form className="chat-input" onSubmit={onSubmit}>
        <textarea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          rows={2}
          placeholder="Ask about flips, like “what could I make with 10M?”"
          aria-label="Message"
        />
        <div className="chat-actions">
          {turns.length > 0 && (
            <button type="button" className="button" onClick={startOver}>
              New chat
            </button>
          )}
          {busy ? (
            <button type="button" className="button" onClick={() => controller.current?.abort()}>
              Stop
            </button>
          ) : (
            <button type="submit" className="button button-primary" disabled={!draft.trim()}>
              Send
            </button>
          )}
        </div>
      </form>
    </section>
  );
}

function lookupStatus(event: Extract<ChatEvent, { type: 'tool' }>): string {
  if (event.name === 'item_history') {
    return `Checking the history of “${event.search}”…`;
  }
  if (event.search) {
    return `Looking up “${event.search}”…`;
  }
  return event.budget !== null ? `Finding flips for ${formatCompact(event.budget)} gp…` : 'Finding flips…';
}
