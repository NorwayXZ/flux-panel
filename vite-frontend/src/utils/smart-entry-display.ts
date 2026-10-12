import type {
  PortForwardOptionGroup,
  PortForwardOption,
} from "./forward-option-groups";

export function ttlDelayText(ttl: number | string): string {
  const seconds = Math.floor(Number(ttl));

  if (!Number.isFinite(seconds) || seconds <= 0) return "";
  if (seconds < 60) return `切换网络后最长约 ${seconds} 秒生效`;
  if (seconds < 3600)
    return `切换网络后最长约 ${Math.ceil(seconds / 60)} 分钟生效`;

  return `切换网络后最长约 ${Math.ceil(seconds / 3600)} 小时生效`;
}

export function limitGroupsToPort<T extends PortForwardOption>(
  groups: PortForwardOptionGroup<T>[],
  port?: number,
): PortForwardOptionGroup<T>[] {
  if (!port) return groups;
  const matched = groups.filter((group) => group.port === port);

  return matched.length ? matched : groups;
}

export function uniqueFallbackNames(
  names: (string | undefined)[],
  ownName?: string,
): string[] {
  return names.filter(
    (name, index): name is string =>
      Boolean(name) && name !== ownName && names.indexOf(name) === index,
  );
}

export function matchZone(domain: string, zones: string[]): string {
  const normalized = domain.trim().toLowerCase().replace(/\.$/, "");

  if (!normalized.includes(".")) return "";

  return (
    zones
      .map((zone) => zone.trim().toLowerCase())
      .filter((zone) => normalized === zone || normalized.endsWith(`.${zone}`))
      .sort((left, right) => right.length - left.length)[0] || ""
  );
}

export function matchProviderZone(
  domain: string,
  zonesByProvider: { providerRefId: string; zones: string[] }[],
): { providerRefId: string; zoneName: string } | null {
  const matches = zonesByProvider
    .map((item) => ({
      providerRefId: item.providerRefId,
      zoneName: matchZone(domain, item.zones),
    }))
    .filter((item) => item.zoneName)
    .sort((left, right) => right.zoneName.length - left.zoneName.length);

  if (!matches.length) return null;
  // The same zone under two accounts cannot be resolved without the user.
  if (matches[1]?.zoneName === matches[0].zoneName) return null;

  return matches[0];
}

export type LineCheckState = "ok" | "pending" | "mismatch" | "failed";

export function lineCheckState(line: {
  providerMatch: boolean;
  publicMatch: boolean;
  publicProbe: { successful: boolean };
}): LineCheckState {
  if (!line.providerMatch) return "mismatch";
  if (!line.publicProbe.successful) return "failed";

  return line.publicMatch ? "ok" : "pending";
}
