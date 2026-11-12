import { describe, expect, it } from 'vitest';
import { dispatchIntent } from '../src/lib/intent';

const form = (intent?: string) => {
  const f = new FormData();
  if (intent !== undefined) f.set('intent', intent);
  return f;
};

describe('dispatchIntent', () => {
  const handlers = { a: () => 'A', b: async () => 'B' };

  it('runs the named handler, sync or async', async () => {
    expect(await dispatchIntent(form('a'), handlers)).toEqual({ known: true, intent: 'a', value: 'A' });
    expect(await dispatchIntent(form('b'), handlers)).toEqual({ known: true, intent: 'b', value: 'B' });
  });
  it('reports unknown, missing and inherited names as unknown', async () => {
    for (const i of ['nope', 'toString', '__proto__', undefined]) {
      expect((await dispatchIntent(form(i), handlers)).known).toBe(false);
    }
  });
  it('lets handler errors propagate', async () => {
    await expect(dispatchIntent(form('x'), { x: () => Promise.reject(new Error('boom')) })).rejects.toThrow('boom');
  });
});
