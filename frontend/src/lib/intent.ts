import { formString } from './forms';

/** Shown when a form post names an intent that has no handler. */
export const UNKNOWN_INTENT_MESSAGE = 'Unknown action.';

export type IntentHandler<R> = (form: FormData) => R | Promise<R>;

export type IntentResult<R> = { known: true; intent: string; value: R } | { known: false; intent: string };

/**
 * Runs the handler named by the form's hidden `intent` field. Pages with several forms post to themselves and
 * pick the handler this way. Only own keys of `handlers` count, so an intent like "toString" is unknown.
 * Errors thrown by a handler propagate to the caller.
 */
export async function dispatchIntent<R>(form: FormData, handlers: Record<string, IntentHandler<R>>): Promise<IntentResult<R>> {
  const intent = formString(form, 'intent');
  if (!Object.hasOwn(handlers, intent)) return { known: false, intent };
  return { known: true, intent, value: await handlers[intent]!(form) };
}
