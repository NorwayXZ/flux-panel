import type { OpenWrtDnsResolver } from "@/api";

export function hasVerifiedRouterDns(
  router: OpenWrtDnsResolver | undefined,
  now = Date.now(),
  scope?: { domain: string; recordType: string },
): boolean {
  if (!router) return false;
  const version = (router.version || "")
    .replace(/^v/, "")
    .split(".")
    .map(Number);
  const supported = version[0] > 2 || (version[0] === 2 && version[1] >= 56);
  const age = now - Number(router.reportedAt || 0);
  const current =
    supported &&
    router.online &&
    Number(router.policyRevision || 0) > 0 &&
    router.appliedRevision === router.policyRevision &&
    Number(router.dnsCheckedAt || 0) > 0 &&
    age >= 0 &&
    age < 60000;
  if (!current) return false;
  if (scope)
    return (
      ["ready", "domain-error", "connection-error"].includes(
        router.dnsStatus || "",
      ) &&
      Boolean(
        router.dnsChecks?.some(
          (check) =>
            check.domain === scope.domain &&
            check.recordType === scope.recordType &&
            check.state === "ready" &&
            check.tcpState !== "failed",
        ),
      )
    );
  return router.dnsReady === true && !router.lastError;
}
