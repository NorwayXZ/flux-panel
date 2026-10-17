let names: Intl.DisplayNames | null = null;

/** Chinese name of a two-letter country code, e.g. US → 美国. */
export function regionName(code: string): string {
  try {
    names = names || new Intl.DisplayNames(["zh-CN"], { type: "region" });

    return names.of(code.toUpperCase()) || code;
  } catch {
    return code;
  }
}
