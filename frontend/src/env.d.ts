/// <reference path="../.astro/types.d.ts" />

declare namespace App {
  interface Locals {
    /** The logged-in user, or null. Set by src/middleware.ts for every request. */
    user: import('./lib/types').UserResponse | null;
    /** True when the session could not be verified because the backend was unreachable. */
    backendDown: boolean;
  }
}
