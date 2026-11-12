import { defineMiddleware } from 'astro:middleware';
import { gate } from './lib/gate';

/** Runs for every request; the logic lives in src/lib/gate.ts (session lookup, then the route rules). */
export const onRequest = defineMiddleware((context, next) => gate(context, next));
