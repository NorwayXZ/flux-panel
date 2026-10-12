// Browsers word a missing lazily loaded page chunk differently; these are the
// messages Chrome, Firefox, Safari and Vite's preload helper produce.
export const isStaleChunkError = (error: unknown): boolean =>
  /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS|ChunkLoadError/i.test(
    error instanceof Error ? `${error.name} ${error.message}` : String(error),
  );
