import type { AstroCookies } from 'astro';
import type { BackendClient } from './backend';
import type { ApiError } from './errors';
import { confirmMismatch, formErrorState, formString } from './forms';
import { dispatchIntent, UNKNOWN_INTENT_MESSAGE } from './intent';
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

const CARD_OF_INTENT: Record<string, AccountCard> = {
  'update-profile': 'profile',
  'change-password': 'password',
  'logout-all': 'sessions',
};

/** The card an error for this intent is shown on. Unknown intents belong to no card. */
export function cardForIntent(intent: string): AccountCard {
  return Object.hasOwn(CARD_OF_INTENT, intent) ? CARD_OF_INTENT[intent]! : 'page';
}

function initialState(user: AccountDeps['user']): AccountState {
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
  const state = initialState(user);
  let status = 400;
  try {
    const result = await dispatchIntent<Response | undefined>(form, {
      'update-profile': async () => {
        const req = buildProfileRequest(user.role, form);
        state.shownName = req.fullName ?? '';
        state.shownDepartment = req.department ?? '';
        await api.updateProfile(req);
        return ctx.redirect('/account?notice=profile');
      },
      'change-password': async () => {
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
      'logout-all': async () => {
        await api.logoutAll();
        endSession(cookies);
        return ctx.redirect('/login?signedOut=all');
      },
    });
    if (!result.known) state.errors.page = UNKNOWN_INTENT_MESSAGE;
    else if (result.value) return { kind: 'response', response: result.value };
  } catch (e) {
    const r = await handleLoadError(e, ctx, { inlineServerErrors: true });
    if (r instanceof Response) return { kind: 'response', response: r };
    applyApiError(state, cardForIntent(formString(form, 'intent')), r);
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
