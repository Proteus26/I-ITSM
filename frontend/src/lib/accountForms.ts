import type { AstroCookies } from 'astro';
import type { BackendClient } from './backend';
import type { ApiError } from './errors';
import { confirmMismatch, formErrorState, formString } from './forms';
import { dispatchIntent, UNKNOWN_INTENT_MESSAGE, type IntentHandler } from './intent';
import { handleLoadError, type LoadErrorContext } from './loadError';
import { endSession, startSession } from './session';
import type { Role, UpdateProfileRequest } from './types';

/** Which card of the /account page shows an error; `page` is for anything that belongs to no card. */
export type AccountCard = 'profile' | 'password' | 'sessions' | 'page';

export interface AccountState {
  /** Messages per card (null = none) and field errors for the forms that have fields. */
  errors: Record<AccountCard, string | null>;
  profileFieldErrors: Record<string, string>;
  passwordFieldErrors: Record<string, string>;
  /** What the profile form shows (the submitted values after a failure). */
  shownName: string;
  shownDepartment: string;
}

export type AccountResult =
  | { kind: 'response'; response: Response }
  | { kind: 'state'; state: AccountState; status: number };

export type AccountApi = Pick<BackendClient, 'updateProfile' | 'changePassword' | 'logoutAll'>;

export interface AccountDeps {
  user: { role: Role; fullName: string; department?: string | null };
  api: AccountApi;
  cookies: AstroCookies;
  ctx: LoadErrorContext;
}

/** The PATCH body: department is sent for employees only (a blank value clears it). */
export function buildProfileRequest(role: Role, form: FormData): UpdateProfileRequest {
  const req: UpdateProfileRequest = { fullName: formString(form, 'fullName') };
  if (role === 'EMPLOYEE') req.department = formString(form, 'department');
  return req;
}

/** The initial /account state: no errors, the form showing the user's current values. */
export function initialAccountState(user: AccountDeps['user']): AccountState {
  return {
    errors: { profile: null, password: null, sessions: null, page: null },
    profileFieldErrors: {},
    passwordFieldErrors: {},
    shownName: user.fullName,
    shownDepartment: user.department ?? '',
  };
}

/**
 * Handles a POST to /account. Success paths return a redirect response (for change-password the cookie is
 * switched to the new token first, so the user stays logged in; for logout-all the cookie is cleared).
 * Failures return the state to render, with each error on the card of the form that failed.
 */
export async function processAccountPost(form: FormData, deps: AccountDeps): Promise<AccountResult> {
  const { user, api, cookies, ctx } = deps;
  const state = initialAccountState(user);
  let status = 400;

  // One entry per intent: the card its errors belong to, and what it does. Unknown intents belong to the page.
  const intents: Record<string, { card: AccountCard; run: IntentHandler<Response | undefined> }> = {
    'update-profile': {
      card: 'profile',
      run: async () => {
        const req = buildProfileRequest(user.role, form);
        state.shownName = req.fullName ?? '';
        state.shownDepartment = req.department ?? '';
        await api.updateProfile(req);
        return ctx.redirect('/account?notice=profile');
      },
    },
    'change-password': {
      card: 'password',
      run: async () => {
        const currentPassword = formString(form, 'currentPassword', false);
        const newPassword = formString(form, 'newPassword', false);
        const mismatch = confirmMismatch(newPassword, formString(form, 'confirmPassword', false));
        if (mismatch) {
          state.passwordFieldErrors = { confirmPassword: mismatch };
          return undefined;
        }
        // The old token is revoked by the backend; switch the cookie to the new one so the user stays logged in.
        startSession(cookies, await api.changePassword({ currentPassword, newPassword }));
        return ctx.redirect('/account?notice=changed');
      },
    },
    'logout-all': {
      card: 'sessions',
      run: async () => {
        await api.logoutAll();
        endSession(cookies);
        return ctx.redirect('/login?loggedOut=all');
      },
    },
  };

  try {
    const result = await dispatchIntent(form, Object.fromEntries(Object.entries(intents).map(([name, i]) => [name, i.run])));
    if (!result.known) state.errors.page = UNKNOWN_INTENT_MESSAGE;
    else if (result.value) return { kind: 'response', response: result.value };
  } catch (e) {
    const r = await handleLoadError(e, ctx, { inlineServerErrors: true });
    if (r instanceof Response) return { kind: 'response', response: r };
    const intent = formString(form, 'intent');
    applyApiError(state, Object.hasOwn(intents, intent) ? intents[intent]!.card : 'page', r);
    status = r.status;
  }
  return { kind: 'state', state, status };
}

function applyApiError(state: AccountState, card: AccountCard, e: ApiError): void {
  const { message, fieldErrors } = formErrorState(e);
  state.errors[card] = message;
  if (card === 'profile') state.profileFieldErrors = fieldErrors;
  if (card === 'password') state.passwordFieldErrors = fieldErrors;
}
