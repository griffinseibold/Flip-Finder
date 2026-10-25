import { afterEach, describe, expect, it, vi } from 'vitest';

import { flipsUrl, parseChatEvent, streamChat, type ChatEvent, type FlipQuery } from './api';

const QUERY: FlipQuery = {
  page: 2,
  size: 50,
  sort: 'estimatedProfit',
  direction: 'desc',
  basis: 'latest',
  search: '',
  membership: 'all',
  maxTradeAgeMinutes: 60,
  minVolume5m: 10,
  budget: null,
};

describe('flipsUrl', () => {
  it('sends every set parameter', () => {
    expect(flipsUrl({ ...QUERY, search: 'gold leaf', budget: 10_000_000 })).toBe(
      '/api/flips?page=2&size=50&sort=estimatedProfit&direction=desc&basis=latest&search=gold+leaf' +
        '&membership=all&maxTradeAgeMinutes=60&minVolume5m=10&budget=10000000',
    );
  });

  it('leaves out an empty search and budget', () => {
    const url = flipsUrl(QUERY);

    expect(url).not.toContain('search=');
    expect(url).not.toContain('budget=');
  });
});

describe('parseChatEvent', () => {
  it('reads the wait for the language model to start', () => {
    expect(parseChatEvent('{"type":"status","message":"The homelab\'s language model is starting."}')).toEqual({
      type: 'status',
      message: "The homelab's language model is starting.",
    });
  });

  it('reads the events the chat already knew', () => {
    expect(parseChatEvent('{"type":"text","text":"Hi"}')).toEqual({ type: 'text', text: 'Hi' });
    expect(parseChatEvent('{"type":"done"}')).toEqual({ type: 'done' });
  });

  it('skips kinds of event it does not know and lines that are not events', () => {
    expect(parseChatEvent('{"type":"progress","percent":50}')).toBeNull();
    expect(parseChatEvent('{"text":"no type"}')).toBeNull();
    expect(parseChatEvent('"text"')).toBeNull();
    expect(parseChatEvent('null')).toBeNull();
    expect(parseChatEvent('not json')).toBeNull();
  });
});

describe('streamChat', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('passes on known events and skips the rest without stopping', async () => {
    const body = [
      '{"type":"status","message":"Starting."}',
      '{"type":"someday","value":1}',
      '{"type":"text","text":"Flip gold leaf."}',
      '{"type":"done"}',
      '',
    ].join('\n');
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { status: 200 })));
    const events: ChatEvent[] = [];

    await streamChat(null, [{ role: 'user', content: 'What should I flip?' }], (event) => events.push(event));

    expect(events.map((event) => event.type)).toEqual(['status', 'text', 'done']);
  });
});
