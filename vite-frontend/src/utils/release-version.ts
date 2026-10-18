export const normalizeVersion = (value: string) => value.trim().replace(/^v/i, "");
export function compareVersions(left: string, right: string): number {
  const parse = (value: string) => {
    const [core, suffix] = normalizeVersion(value).split("-", 2);
    return { core: core.split(".").map(Number), suffix: suffix?.split(".") };
  };
  const a = parse(left), b = parse(right);
  for (let i = 0; i < Math.max(a.core.length, b.core.length); i++) {
    const difference = (a.core[i] || 0) - (b.core[i] || 0);
    if (difference) return difference;
  }
  if (!a.suffix || !b.suffix) return a.suffix ? -1 : b.suffix ? 1 : 0;
  for (let i = 0; i < Math.max(a.suffix.length, b.suffix.length); i++) {
    const x = a.suffix[i], y = b.suffix[i];
    if (x === y) continue;
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    if (/^\d+$/.test(x) && /^\d+$/.test(y)) return Number(x) - Number(y);
    if (/^\d+$/.test(x)) return -1;
    if (/^\d+$/.test(y)) return 1;
    return x < y ? -1 : 1;
  }
  return 0;
}
export interface PublishedRelease {
  tag_name?: string; name?: string; html_url?: string; published_at?: string;
  draft?: boolean; prerelease?: boolean;
}
export function selectRelease(releases: PublishedRelease[], current: string) {
  const betaChannel = normalizeVersion(current).includes("-");
  return releases.filter(r => !r.draft && r.tag_name && /^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(r.tag_name)
    && (betaChannel || !r.prerelease && !r.tag_name.includes("-")))
    .sort((a, b) => compareVersions(b.tag_name!, a.tag_name!))[0];
}
