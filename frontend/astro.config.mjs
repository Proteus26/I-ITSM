// @ts-check
import { defineConfig } from 'astro/config';
import node from '@astrojs/node';

// https://astro.build/config
export default defineConfig({
  // Server-rendered by default: pages can read the session/role per request.
  // Opt individual pages into static generation with `export const prerender = true`.
  output: 'server',
  adapter: node({ mode: 'standalone' }),
  server: { host: true, port: 4321 },
});
