export interface CnixDiagnosticCheck {
  description: string;
  targetAddress: string;
  state: "passed" | "failed" | "pending";
  message: string;
  elapsedMs?: number;
}
export interface CnixDiagnosticReport {
  channelReady?: boolean;
  externalAddress?: string;
  exitAddress?: string;
  businessCount?: number;
  checks?: CnixDiagnosticCheck[];
}
export default function CnixTunnelDiagnosis({
  report,
}: {
  report: CnixDiagnosticReport;
}) {
  return (
    <section className="space-y-4" aria-label="CNIX共享通道诊断">
      <div
        className={`rounded-xl border p-4 ${report.channelReady ? "border-success bg-success-50" : "border-warning bg-warning-50"}`}
      >
        <h3 className="font-semibold">
          {report.channelReady
            ? "共享通道已验证，可以添加业务"
            : "共享通道尚未验证通过"}
        </h3>
        <p className="mt-2 break-all font-mono text-sm">
          {report.externalAddress || "待填写CNIX加速入口"} →{" "}
          {report.exitAddress}
        </p>
        <p className="mt-2 text-xs text-default-600">
          检查实际加速入口、共享出口证书及认证保护。握手耗时包含 TLS
          和协议交互，不表示业务延迟或吞吐量。
        </p>
      </div>
      {(report.checks || []).map((check, index) => (
        <article key={index} className="rounded-xl border border-divider p-4">
          <div className="flex items-center justify-between gap-3">
            <h4 className="font-medium">{check.description}</h4>
            <span
              className={
                check.state === "passed"
                  ? "text-success"
                  : check.state === "pending"
                    ? "text-warning"
                    : "text-danger"
              }
            >
              {check.state === "passed"
                ? "通过"
                : check.state === "pending"
                  ? "待配置"
                  : "未通过"}
            </span>
          </div>
          <p className="mt-2 break-all font-mono text-sm">
            {check.targetAddress || "未配置"}
          </p>
          <p className="mt-2 text-sm text-default-600">{check.message}</p>
          {check.state === "passed" && typeof check.elapsedMs === "number" && (
            <p className="mt-2 text-xs text-default-500">
              本次共享通道握手：{check.elapsedMs.toFixed(0)} ms
            </p>
          )}
        </article>
      ))}
      <p className="text-xs text-default-500">
        已关联 {report.businessCount || 0}{" "}
        条业务。共享通道可以先独立建立；添加业务后，在转发管理中检查各自落地和客户端协议。
      </p>
    </section>
  );
}
