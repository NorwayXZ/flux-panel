import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Checkbox } from "@heroui/checkbox";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Radio, RadioGroup } from "@heroui/radio";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import {
  CreditCard,
  ExternalLink,
  Home,
  RefreshCw,
  Shuffle,
  Wallet,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  getMysteriumCountries,
  getMysteriumOverview,
  reconnectMysterium,
  refreshMysterium,
  saveMysteriumSettings,
  setupMysteriumNode,
  topupMysterium,
  type MysteriumAccount,
  type MysteriumCountry,
  type MysteriumSettings,
} from "@/api";
import { formatDateTime } from "@/utils/format";
import { regionName } from "@/utils/region-name";

const TERMS_URL =
  "https://github.com/mysteriumnetwork/terms/blob/master/documents/TERMS_END_USER.md";

const stateLabel: Record<
  string,
  { text: string; color: "success" | "warning" | "danger" | "default" }
> = {
  connected: { text: "已连接", color: "success" },
  connecting: { text: "连接中", color: "warning" },
  failed: { text: "连接失败", color: "danger" },
  suspended: { text: "已暂停", color: "default" },
};

const traffic = (bytes: number) =>
  bytes >= 1024 ** 3
    ? `${(bytes / 1024 ** 3).toFixed(2)} GB`
    : `${(bytes / 1024 ** 2).toFixed(1)} MB`;

/** Mysterium residential exits: per-node paid accounts, connections, budget and top-ups. */
export default function MysteriumPanel() {
  const [settings, setSettings] = useState<MysteriumSettings | null>(null);
  const [accounts, setAccounts] = useState<MysteriumAccount[]>([]);
  const [nodes, setNodes] = useState<
    Array<{ id: number; name: string; online: boolean; supported: boolean }>
  >([]);
  const [countries, setCountries] = useState<MysteriumCountry[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [agree, setAgree] = useState(false);
  const [budget, setBudget] = useState("20");
  const [lowBalance, setLowBalance] = useState("10");
  const [setupNode, setSetupNode] = useState("");
  const [topupTarget, setTopupTarget] = useState<MysteriumAccount | null>(null);
  const [gateway, setGateway] = useState("paypal");
  const [amount, setAmount] = useState("10");
  const [payCountry, setPayCountry] = useState("CN");
  const [payment, setPayment] = useState<{
    payAmount: string;
    payCurrency: string;
    receiveMyst: string;
    paymentUrl: string;
  } | null>(null);

  const load = async () => {
    const response = await getMysteriumOverview();

    if (response.code === 0 && response.data) {
      setSettings(response.data.settings);
      setAccounts(response.data.accounts || []);
      setNodes(response.data.nodes || []);
      setBudget(String(response.data.settings.monthlyBudgetUsd));
      setLowBalance(String(response.data.settings.lowBalanceMyst));
    } else toast.error(response.msg || "读取 Mysterium 状态失败");
    setLoading(false);
  };

  useEffect(() => {
    void load();
    void getMysteriumCountries().then((response) => {
      if (response.code === 0) setCountries(response.data || []);
    });
  }, []);

  const save = async (patch: Parameters<typeof saveMysteriumSettings>[0]) => {
    setBusy("settings");
    const response = await saveMysteriumSettings(patch);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    toast.success("已保存");
    void load();
  };

  const enableOn = async () => {
    if (!setupNode) return;
    setBusy("setup");
    toast("正在节点上安装并启动 Mysterium，第一次需要 1～2 分钟…", {
      duration: 5000,
    });
    const response = await setupMysteriumNode(Number(setupNode));

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "启用失败");
    toast.success("已在节点上启用，充值后即可使用");
    setSetupNode("");
    void load();
  };

  const refresh = async (account: MysteriumAccount) => {
    setBusy(`refresh-${account.nodeId}`);
    const response = await refreshMysterium(account.nodeId);

    setBusy("");
    if (response.code !== 0) toast.error(response.msg || "刷新失败");
    void load();
  };

  const reconnect = async (account: MysteriumAccount, country: string) => {
    setBusy(`reconnect-${account.nodeId}-${country}`);
    const response = await reconnectMysterium(account.nodeId, country);

    setBusy("");
    if (response.code !== 0) toast.error(response.msg || "换节点失败");
    else toast.success(`${regionName(country)} 已换到另一个家宽节点`);
    void load();
  };

  const createTopup = async () => {
    if (!topupTarget) return;
    setBusy("topup");
    const response = await topupMysterium({
      nodeId: topupTarget.nodeId,
      gateway: gateway as "paypal" | "stripe" | "coingate",
      amountUsd: Number(amount),
      country: payCountry.trim().toUpperCase(),
    });

    setBusy("");
    if (response.code !== 0)
      return toast.error(response.msg || "创建充值订单失败");
    setPayment(response.data);
  };

  if (loading)
    return (
      <div className="flex min-h-40 items-center justify-center">
        <Spinner />
      </div>
    );

  const rate = settings?.mystUsd || 0.08;
  const enabled = Boolean(settings?.enabled);
  const available = nodes.filter(
    (node) =>
      node.online &&
      node.supported &&
      !accounts.some((account) => account.nodeId === node.id),
  );
  const budgetUsd = settings?.monthlyBudgetUsd || 0;

  return (
    <div className="space-y-5">
      <section className="space-y-3 rounded-2xl border border-divider bg-content1 p-4">
        <div className="flex flex-wrap items-start gap-3">
          <Home className="mt-0.5 shrink-0 text-primary" size={22} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold text-foreground">
                Mysterium 家宽
              </span>
              <Chip color="warning" size="sm" variant="flat">
                按流量计费
              </Chip>
              {enabled && (
                <Chip color="success" size="sm" variant="flat">
                  已启用
                </Chip>
              )}
            </div>
            <p className="mt-1 max-w-3xl text-sm text-default-500">
              来自 Mysterium
              去中心化网络、由个人用家庭宽带运营的出口，覆盖一百多个国家。在私人代理的“出口与分流”里选“Mysterium
              家宽 ·
              国家”即可使用：保存时面板会在该节点自动连接这个国家质量最好的家宽节点，连不通会自动换一个。费用约每
              GB {(5.0 * rate).toFixed(2)}～{(5.5 * rate).toFixed(2)}{" "}
              美元（上传和下载都计费），从节点的 Mysterium
              账户余额里扣；每台节点一个账户，需要分别充值。只支持 TCP，UDP（如
              QUIC、语音）走不了这个出口。需要节点 Agent{" "}
              {settings?.agentVersion} 或更高版本。
            </p>
            <p className="mt-1 text-xs text-default-400">
              MYST 当前价格约 {rate.toFixed(4)} 美元
            </p>
          </div>
        </div>

        <ol className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
          {[
            {
              done: enabled,
              title: "启用",
              text: "勾选同意用户条款、设好每月预算，点“启用 Mysterium 家宽”。",
            },
            {
              done: accounts.length > 0,
              title: "在节点上启用",
              text: "在下方“节点账户”选一台海外节点，点“在节点上启用”，自动安装并开一个账户。",
            },
            {
              done: accounts.some((account) => account.balance > 0),
              title: "充值 / 续费",
              text: "点节点卡片上的“充值”，在 PayPal 或信用卡页面付款，5～10 分钟到账。以后余额不够时同样点“充值”续费，不会自动扣款。",
            },
            {
              done: accounts.some((account) => account.connections.length > 0),
              title: "在私人代理里使用",
              text: "打开该节点上代理的“出口与分流”，规则出口或“其余流量”选“Mysterium 家宽 · 国家”，保存。",
            },
          ].map((step, index) => (
            <li
              key={step.title}
              className={`rounded-xl border px-3 py-2 ${step.done ? "border-success/40 bg-success-50/50 dark:bg-success-500/10" : "border-divider"}`}
            >
              <div className="flex items-center gap-2 text-sm font-medium">
                <span
                  className={`flex h-5 w-5 items-center justify-center rounded-full text-xs ${step.done ? "bg-success text-white" : "bg-default-200 text-default-600"}`}
                >
                  {step.done ? "✓" : index + 1}
                </span>
                {step.title}
              </div>
              <p className="mt-1 text-xs text-default-500">{step.text}</p>
            </li>
          ))}
        </ol>

        {!enabled ? (
          <div className="space-y-3 rounded-xl bg-default-50 p-3 dark:bg-default-100/60">
            <Checkbox
              isSelected={agree || Boolean(settings?.termsAgreedAt)}
              size="sm"
              onValueChange={setAgree}
            >
              <span className="text-sm">
                我已阅读并同意 Mysterium 的
                <a
                  className="mx-1 text-primary underline"
                  href={TERMS_URL}
                  rel="noreferrer"
                  target="_blank"
                >
                  用户条款
                </a>
                （不得用于违法用途；出口是他人的家庭网络）
              </span>
            </Checkbox>
            <div className="flex flex-wrap items-end gap-3">
              <Input
                className="w-60"
                description="每台节点每月最多花多少，超过自动暂停"
                endContent={
                  <span className="whitespace-nowrap text-xs text-default-400">
                    美元/月
                  </span>
                }
                label="每月预算"
                size="sm"
                type="number"
                value={budget}
                onValueChange={setBudget}
              />
              <Button
                color="primary"
                isDisabled={!agree && !settings?.termsAgreedAt}
                isLoading={busy === "settings"}
                onPress={() =>
                  void save({
                    enabled: true,
                    agreeTerms: true,
                    monthlyBudgetUsd: Number(budget),
                  })
                }
              >
                启用 Mysterium 家宽
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex flex-wrap items-end gap-3">
            <Input
              className="w-56"
              description="每台节点，超过自动暂停"
              endContent={
                <span className="whitespace-nowrap text-xs text-default-400">
                  美元/月
                </span>
              }
              label="每月预算"
              size="sm"
              type="number"
              value={budget}
              onValueChange={setBudget}
            />
            <Input
              className="w-56"
              description="低于此余额时告警"
              endContent={
                <span className="whitespace-nowrap text-xs text-default-400">
                  MYST
                </span>
              }
              label="余额提醒"
              size="sm"
              type="number"
              value={lowBalance}
              onValueChange={setLowBalance}
            />
            <Button
              isLoading={busy === "settings"}
              variant="flat"
              onPress={() =>
                void save({
                  monthlyBudgetUsd: Number(budget),
                  lowBalanceMyst: Number(lowBalance),
                })
              }
            >
              保存
            </Button>
            <Button
              className="ml-auto"
              color="danger"
              variant="light"
              onPress={() => {
                if (
                  window.confirm(
                    "停用后，私人代理不能再选 Mysterium 家宽，已有的连接会在几分钟内断开。确定停用吗？",
                  )
                )
                  void save({ enabled: false });
              }}
            >
              停用
            </Button>
          </div>
        )}
      </section>

      {enabled && (
        <section className="space-y-3">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 className="text-base font-semibold text-foreground">
                节点账户
              </h2>
              <p className="mt-1 text-sm text-default-500">
                每台用到 Mysterium 的节点有自己的账户和余额。私人代理第一次选
                Mysterium 家宽时会自动启用，也可以先在这里启用并充值。
              </p>
            </div>
            <div className="flex items-end gap-2">
              <Select
                aria-label="选择节点"
                className="w-56"
                isDisabled={available.length === 0}
                placeholder={
                  available.length === 0
                    ? "没有可启用的节点"
                    : "选择要启用的节点"
                }
                selectedKeys={setupNode ? [setupNode] : []}
                size="sm"
                onSelectionChange={(keys) =>
                  setSetupNode(String(Array.from(keys)[0] || ""))
                }
              >
                {available.map((node) => (
                  <SelectItem key={String(node.id)}>{node.name}</SelectItem>
                ))}
              </Select>
              <Button
                color="primary"
                isDisabled={!setupNode}
                isLoading={busy === "setup"}
                size="sm"
                onPress={enableOn}
              >
                在节点上启用
              </Button>
            </div>
          </div>

          {accounts.length === 0 ? (
            <div className="rounded-xl border border-dashed border-divider px-6 py-6 text-center text-sm text-default-500">
              还没有节点启用
              Mysterium。选一台海外节点启用并充值后，就能在私人代理里使用。
            </div>
          ) : (
            <div className="grid gap-3 xl:grid-cols-2">
              {accounts.map((account) => {
                const spentRatio =
                  budgetUsd > 0
                    ? Math.min(1, account.monthSpentUsd / budgetUsd)
                    : 0;

                return (
                  <div
                    key={account.nodeId}
                    className="space-y-3 rounded-2xl border border-divider bg-content1 p-4"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-foreground">
                          {account.nodeName}
                        </span>
                        {account.suspended ? (
                          <Chip color="danger" size="sm" variant="flat">
                            本月超预算，已暂停
                          </Chip>
                        ) : account.registration === "Registered" ? (
                          <Chip color="success" size="sm" variant="flat">
                            正常
                          </Chip>
                        ) : (
                          <Chip color="warning" size="sm" variant="flat">
                            待充值激活
                          </Chip>
                        )}
                      </div>
                      <div className="flex gap-1">
                        <Button
                          color="primary"
                          size="sm"
                          startContent={<Wallet size={14} />}
                          variant="flat"
                          onPress={() => {
                            setPayment(null);
                            setTopupTarget(account);
                          }}
                        >
                          充值
                        </Button>
                        <Button
                          isIconOnly
                          aria-label="刷新"
                          isLoading={busy === `refresh-${account.nodeId}`}
                          size="sm"
                          variant="light"
                          onPress={() => void refresh(account)}
                        >
                          <RefreshCw size={14} />
                        </Button>
                      </div>
                    </div>
                    <div className="grid grid-cols-3 gap-3 text-sm">
                      <div>
                        <div className="text-xs text-default-500">余额</div>
                        <div className="font-semibold">
                          {account.balance.toFixed(2)} MYST
                        </div>
                        <div className="text-xs text-default-400">
                          ≈ ${account.balanceUsd.toFixed(2)}
                        </div>
                      </div>
                      <div>
                        <div className="text-xs text-default-500">本月已花</div>
                        <div className="font-semibold">
                          ${account.monthSpentUsd.toFixed(2)}
                        </div>
                        <div className="text-xs text-default-400">
                          预算 ${budgetUsd}
                        </div>
                      </div>
                      <div>
                        <div className="text-xs text-default-500">
                          可用流量约
                        </div>
                        <div className="font-semibold">
                          {(account.balance / 5.3).toFixed(1)} GB
                        </div>
                        <div className="text-xs text-default-400">
                          按每 GB 约 5.3 MYST
                        </div>
                      </div>
                    </div>
                    {budgetUsd > 0 && (
                      <div className="h-1.5 overflow-hidden rounded-full bg-default-100">
                        <div
                          className={`h-full ${spentRatio >= 1 ? "bg-danger" : spentRatio > 0.8 ? "bg-warning" : "bg-primary"}`}
                          style={{ width: `${spentRatio * 100}%` }}
                        />
                      </div>
                    )}
                    {account.connections.length === 0 ? (
                      <p className="text-xs text-default-500">
                        暂无连接。私人代理选了这台节点的 Mysterium
                        家宽后会自动连接。
                      </p>
                    ) : (
                      <div className="divide-y divide-divider rounded-xl border border-divider">
                        {account.connections.map((connection) => {
                          const state =
                            stateLabel[connection.state] || stateLabel.failed;

                          return (
                            <div
                              key={connection.country}
                              className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm"
                            >
                              <span className="w-20 font-medium">
                                {regionName(connection.country)}
                              </span>
                              <Chip
                                color={state.color}
                                size="sm"
                                variant="flat"
                              >
                                {state.text}
                              </Chip>
                              <span
                                className="min-w-0 flex-1 truncate text-xs text-default-500"
                                title={connection.lastError || undefined}
                              >
                                {connection.state === "failed" &&
                                connection.lastError
                                  ? connection.lastError
                                  : [
                                      connection.exitIp,
                                      connection.city,
                                      connection.isp,
                                      connection.latencyMs
                                        ? `${connection.latencyMs} ms`
                                        : null,
                                      `已用 ${traffic(connection.bytes)}`,
                                    ]
                                      .filter(Boolean)
                                      .join(" · ")}
                              </span>
                              <Button
                                isDisabled={account.suspended}
                                isLoading={
                                  busy ===
                                  `reconnect-${account.nodeId}-${connection.country}`
                                }
                                size="sm"
                                startContent={<Shuffle size={13} />}
                                title="换一个同国家的家宽节点"
                                variant="light"
                                onPress={() =>
                                  void reconnect(account, connection.country)
                                }
                              >
                                换节点
                              </Button>
                            </div>
                          );
                        })}
                      </div>
                    )}
                    <div className="truncate font-mono text-xs text-default-400">
                      账户 {account.identity} · 更新于{" "}
                      {formatDateTime(account.lastStatusAt, "-")}
                    </div>
                    {account.lastError && (
                      <p className="text-xs text-danger">{account.lastError}</p>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </section>
      )}

      {countries.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-base font-semibold text-foreground">可用国家</h2>
          <p className="text-sm text-default-500">
            按优质家宽节点（质量评分 ≥ 2、带宽 ≥ 20 Mbps）数量排序，数据来自
            Mysterium 公开节点目录，每小时更新。
          </p>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4 2xl:grid-cols-6">
            {countries.slice(0, 36).map((country) => (
              <div
                key={country.country}
                className="rounded-xl border border-divider bg-content1 px-3 py-2"
              >
                <div className="text-sm font-medium">
                  {regionName(country.country)}
                  <span className="ml-1 text-xs text-default-400">
                    {country.country}
                  </span>
                </div>
                <div className="text-xs text-default-500">
                  {country.goodNodes} 个优质节点 · ${" "}
                  {country.priceUsdPerGib.toFixed(2)}/GB
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      <Modal
        isOpen={Boolean(topupTarget)}
        size="lg"
        onOpenChange={(open) => !open && setTopupTarget(null)}
      >
        <ModalContent>
          <ModalHeader>为“{topupTarget?.nodeName}”充值</ModalHeader>
          <ModalBody className="space-y-3">
            {!payment ? (
              <>
                <RadioGroup
                  label="付款方式"
                  orientation="horizontal"
                  size="sm"
                  value={gateway}
                  onValueChange={setGateway}
                >
                  <Radio value="paypal">PayPal</Radio>
                  <Radio value="stripe">信用卡（Stripe）</Radio>
                  <Radio value="coingate">加密货币</Radio>
                </RadioGroup>
                <div className="flex gap-3">
                  <Input
                    description={`最低 ${gateway === "coingate" ? 10 : 6.3} 美元`}
                    endContent={<span className="text-xs">美元</span>}
                    label="金额"
                    size="sm"
                    type="number"
                    value={amount}
                    onValueChange={setAmount}
                  />
                  <Input
                    className="w-40"
                    description="付款人所在国家代码"
                    label="国家"
                    size="sm"
                    value={payCountry}
                    onValueChange={setPayCountry}
                  />
                </div>
                <p className="text-xs text-default-500">
                  约可得 {(Number(amount || 0) / rate).toFixed(0)}{" "}
                  MYST（实际金额以付款页为准，PayPal、信用卡会另加手续费）。付款在
                  PayPal / Stripe / CoinGate
                  的官方页面完成，面板不经手任何付款信息。
                </p>
              </>
            ) : (
              <div className="space-y-3">
                <div className="rounded-xl bg-default-50 p-3 text-sm dark:bg-default-100/60">
                  需支付{" "}
                  <span className="font-semibold">
                    {payment.payAmount} {payment.payCurrency}
                  </span>
                  ，到账{" "}
                  <span className="font-semibold">
                    {payment.receiveMyst} MYST
                  </span>
                  。
                </div>
                <Button
                  as="a"
                  color="primary"
                  endContent={<ExternalLink size={14} />}
                  href={payment.paymentUrl}
                  rel="noreferrer"
                  startContent={<CreditCard size={16} />}
                  target="_blank"
                >
                  打开付款页
                </Button>
                <p className="text-xs text-default-500">
                  在新页面完成付款，一般 5～10
                  分钟到账；之后点账户上的刷新按钮查看余额。链接几小时后失效，过期请重新创建。
                </p>
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setTopupTarget(null)}>
              关闭
            </Button>
            {!payment && (
              <Button
                color="primary"
                isLoading={busy === "topup"}
                onPress={createTopup}
              >
                创建付款链接
              </Button>
            )}
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
