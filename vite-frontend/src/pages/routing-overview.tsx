import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { AlertTriangle, CheckCircle2, CircleHelp, ExternalLink, RefreshCw, Route, ShieldCheck, Split, Workflow } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import {
  getAggregationOverview,
  getCrossEntryGroups,
  getForwardList,
  getPortLedger,
  getSmartEntryOverview,
  getSourceIpEntryOverview,
  type PortLedgerEntry,
} from '@/api';

type Snapshot = {
  forwards: any[];
  smartGroups: any[];
  sourceGroups: any[];
  failoverGroups: any[];
  aggregationGroups: any[];
  ledger: PortLedgerEntry[];
};

type Finding = {
  level: 'warning' | 'info';
  title: string;
  detail: string;
  links?: Array<{ label: string; path: string }>;
};

type CapabilityRow = {
  feature: string;
  agent: string;
  traffic: string;
  dns: string;
  udp: string;
  userAuth: string;
};

const emptySnapshot: Snapshot = {
  forwards: [],
  smartGroups: [],
  sourceGroups: [],
  failoverGroups: [],
  aggregationGroups: [],
  ledger: [],
};

const modules = [
  {
    title: '转发管理',
    path: '/forward',
    icon: Route,
    tone: 'primary' as const,
    role: '承载与线路策略',
    detail: '创建实际入口端口。单线路、主备、低延迟和多线路并发策略都在这里产生实际转发。',
    boundary: '这是底层承载层；其他调度功能通常围绕它选择、切换或复用线路。',
  },
  {
    title: '三网优化',
    path: '/smart-entry',
    icon: Split,
    tone: 'secondary' as const,
    role: '按运营商 DNS 返回入口',
    detail: '使用 DNSPod 或阿里云 DNS 的运营商线路解析，让电信、联通、移动获得不同入口。',
    boundary: '依赖权威 DNS 和缓存刷新；不读取客户端真实 IP，也不会绕过 DNS 缓存。',
  },
  {
    title: '来源 IP 分流',
    path: '/source-ip-entry',
    icon: Workflow,
    tone: 'warning' as const,
    role: '统一入口读取来源 IP',
    detail: '客户端先连接统一入口，入口 Agent 按真实来源 IP 选择后端入口。',
    boundary: '不依赖客户端 DNS，但统一入口会承载中转流量；当前只支持 TCP。',
  },
  {
    title: '入口容灾',
    path: '/cross-entry-failover',
    icon: ShieldCheck,
    tone: 'success' as const,
    role: '按健康度切换 DNS 入口',
    detail: '监测多个前置入口，按连接、延迟、P95、抖动和丢包等规则进行主备切换。',
    boundary: '切换主要影响新连接；DNS TTL、运营商缓存和回切保护期都会影响实际生效时间。',
  },
  {
    title: '多线路并发调度',
    path: '/multi-line-aggregation',
    icon: Workflow,
    tone: 'primary' as const,
    role: '多连接加权分配',
    detail: '对同一入口节点的多条隧道做并发会话调度，按带宽、延迟、丢包和抖动调整权重。',
    boundary: '它不是把单个 TCP 连接拆到多条线路上；单连接速度仍由该连接实际使用的路径决定。',
  },
];

const capabilityRows: CapabilityRow[] = [
  {
    feature: '转发管理',
    agent: '基础 Agent',
    traffic: '按所选隧道/路径产生',
    dns: '否',
    udp: '取决于协议模式',
    userAuth: '支持隧道/节点/端口授权',
  },
  {
    feature: '三网优化',
    agent: '基础转发能力',
    traffic: '不额外中转',
    dns: '依赖 DNSPod/阿里云线路解析',
    udp: '取决于被选转发',
    userAuth: '管理员调度，不直接授权',
  },
  {
    feature: '来源 IP 分流',
    agent: '入口 Agent >= 2.42.3',
    traffic: '统一入口承载连接流量',
    dns: '否',
    udp: '当前仅 TCP',
    userAuth: '管理员调度，不直接授权',
  },
  {
    feature: '入口容灾',
    agent: '面板探测无额外要求；远程探测 >= 2.19.0',
    traffic: '不额外中转',
    dns: '依赖 Cloudflare 记录切换',
    udp: '健康判断以 TCP 为主',
    userAuth: '管理员调度，不直接授权',
  },
  {
    feature: '多线路并发调度',
    agent: '基础转发能力',
    traffic: '按被分配线路产生',
    dns: '否',
    udp: '取决于协议模式',
    userAuth: '管理员调度，不直接授权',
  },
  {
    feature: '内网组建与出口',
    agent: '自动组网 >= 2.44.0；原生内网/出口 >= 2.45.0',
    traffic: '中继/出口节点会产生流量',
    dns: '仅域名发布或动态解析时依赖',
    udp: 'WireGuard 组网依赖 UDP',
    userAuth: '当前以管理员组网为主',
  },
];

const typeLabel: Record<string, string> = {
  forward_entry: '转发入口',
  source_ip_entry: '来源 IP 分流',
  nft_forward: 'nftables 转发',
  private_proxy: '私人代理',
  domain_ingress: '域名入口',
  home_proxy: '家庭网络中转',
  network_route_application: '多跳出口',
  tunnel_hop: '隧道跳点',
  pool_control: '端口池控制端口',
  pool_range: '端口池范围',
};

const toneClass: Record<string, string> = {
  primary: 'bg-primary-100 text-primary-600 dark:bg-primary-500/15 dark:text-primary-300',
  secondary: 'bg-secondary-100 text-secondary-600 dark:bg-secondary-500/15 dark:text-secondary-300',
  success: 'bg-success-100 text-success-600 dark:bg-success-500/15 dark:text-success-300',
  warning: 'bg-warning-100 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300',
};

const routePath = (path: string, navigate: ReturnType<typeof useNavigate>) => (
  <Button
    size="sm"
    variant="flat"
    endContent={<ExternalLink size={14} />}
    onPress={() => navigate(path)}
  >
    查看配置
  </Button>
);

export default function RoutingOverviewPage() {
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<Snapshot>(emptySnapshot);
  const [loading, setLoading] = useState(true);
  const [errors, setErrors] = useState<string[]>([]);
  const [refreshedAt, setRefreshedAt] = useState<number>();

  const load = useCallback(async () => {
    setLoading(true);
    const next = { ...emptySnapshot };
    const nextErrors: string[] = [];

    const read = async <T,>(label: string, task: () => Promise<{ code: number; msg?: string; data?: T }>, assign: (value: T) => void) => {
      try {
        const response = await task();
        if (response.code === 0 && response.data !== undefined) {
          assign(response.data);
        } else {
          nextErrors.push(`${label}：${response.msg || '接口未返回数据'}`);
        }
      } catch (error) {
        nextErrors.push(`${label}：${error instanceof Error ? error.message : '请求失败'}`);
      }
    };

    await Promise.all([
      read('转发管理', getForwardList, value => { next.forwards = Array.isArray(value) ? value : []; }),
      read('三网优化', getSmartEntryOverview, value => { next.smartGroups = value?.groups || []; }),
      read('来源 IP 分流', getSourceIpEntryOverview, value => { next.sourceGroups = value?.groups || []; }),
      read('入口容灾', getCrossEntryGroups, value => { next.failoverGroups = value?.groups || []; }),
      read('多线路并发调度', getAggregationOverview, value => { next.aggregationGroups = value?.groups || []; }),
      read('全局端口账本', () => getPortLedger(), value => { next.ledger = value?.entries || []; }),
    ]);

    setSnapshot(next);
    setErrors(nextErrors);
    setRefreshedAt(Date.now());
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const findings = useMemo<Finding[]>(() => {
    const result: Finding[] = [];

    const dnsOwners = new Map<string, string[]>();
    for (const group of snapshot.smartGroups) {
      const key = `${String(group.domain || '').toLowerCase()}|${group.recordType || 'A'}`;
      if (!key.startsWith('|')) dnsOwners.set(key, [...(dnsOwners.get(key) || []), `三网优化：${group.name}`]);
    }
    for (const group of snapshot.failoverGroups) {
      const key = `${String(group.domain || '').toLowerCase()}|${group.recordType || 'A'}`;
      if (!key.startsWith('|')) dnsOwners.set(key, [...(dnsOwners.get(key) || []), `入口容灾：${group.name}`]);
    }
    for (const [key, owners] of dnsOwners) {
      if (owners.length < 2) continue;
      const [domain, recordType] = key.split('|');
      result.push({
        level: 'warning',
        title: `同一 DNS 记录被多个调度模块使用：${domain} · ${recordType}`,
        detail: `${owners.join('；')}。这不一定已经冲突，但它们可能分别更新同一业务记录，建议一个域名记录只交给一个调度模块管理。`,
        links: [{ label: '三网优化', path: '/smart-entry' }, { label: '入口容灾', path: '/cross-entry-failover' }],
      });
    }

    const occupied = new Map<string, PortLedgerEntry[]>();
    for (const entry of snapshot.ledger) {
      if (!['occupied', 'reserved'].includes(entry.status)) continue;
      const key = `${entry.namespace}:${entry.portStart}-${entry.portEnd}`;
      occupied.set(key, [...(occupied.get(key) || []), entry]);
    }
    for (const entries of occupied.values()) {
      const distinctResources = new Map(entries.map(entry => [`${entry.type}:${entry.resourceId}`, entry]));
      if (distinctResources.size < 2) continue;
      const listed = Array.from(distinctResources.values()).slice(0, 3);
      result.push({
        level: 'warning',
        title: `发现端口账本重复占用：${listed[0].nodeName} · ${listed[0].portStart}`,
        detail: listed.map(entry => `${typeLabel[entry.type] || entry.type}：${entry.resourceName}`).join('；') + (distinctResources.size > 3 ? '；还有其他记录' : ''),
        links: [{ label: '打开资源中心', path: '/port-resources' }],
      });
    }

    const forwardSetOwners = new Map<string, Array<{ module: string; name: string; path: string }>>();
    const addForwardSet = (module: string, name: string, path: string, ids: Array<number | string | undefined>) => {
      const normalized = Array.from(new Set(ids.map(Number).filter(id => Number.isFinite(id) && id > 0))).sort((a, b) => a - b);
      if (normalized.length < 2) return;
      const key = normalized.join(',');
      forwardSetOwners.set(key, [...(forwardSetOwners.get(key) || []), { module, name, path }]);
    };
    for (const group of snapshot.smartGroups) {
      addForwardSet('三网优化', group.name, '/smart-entry', (group.routes || []).map((route: any) => route.forwardId));
    }
    for (const group of snapshot.failoverGroups) {
      addForwardSet('入口容灾', group.name, '/cross-entry-failover', (group.members || []).map((member: any) => member.forwardId));
    }
    for (const group of snapshot.sourceGroups) {
      addForwardSet('来源 IP 分流', group.name, '/source-ip-entry', (group.routes || []).map((route: any) => route.backendForwardId));
    }
    for (const [forwardIds, owners] of forwardSetOwners) {
      if (new Set(owners.map(owner => owner.module)).size < 2) continue;
      result.push({
        level: 'warning',
        title: `同一组入口转发被多个调度策略接管：${forwardIds}`,
        detail: owners.map(owner => `${owner.module}：${owner.name}`).join('；') + '。新保存操作会阻断这种配置，现有配置请确认保留哪个调度策略。',
        links: Array.from(new Map(owners.map(owner => [owner.path, { label: owner.module, path: owner.path }])).values()),
      });
    }

    if (snapshot.sourceGroups.some(group => group.enabled && group.state === 'active')) {
      result.push({
        level: 'info',
        title: '来源 IP 分流会经过统一入口',
        detail: '它解决的是“按真实来源 IP 选后端”的问题，不是无流量的 DNS 跳转。统一入口会承载连接字节；如果目标是尽量减少中转，应优先考虑三网 DNS 或入口容灾。',
        links: [{ label: '查看来源 IP 分流', path: '/source-ip-entry' }, { label: '查看三网优化', path: '/smart-entry' }],
      });
    }

    if (snapshot.aggregationGroups.length > 0) {
      result.push({
        level: 'info',
        title: '多线路并发调度不会提升每一个单连接的上限',
        detail: '当前实现是多连接加权分配。测速、下载器多连接或多用户并发更容易看到收益；单线程仍受单条路径限制。',
        links: [{ label: '查看并发调度', path: '/multi-line-aggregation' }],
      });
    }

    if (result.length === 0) {
      result.push({
        level: 'info',
        title: '暂未发现明显的配置重叠',
        detail: '这是基于当前面板数据和端口账本的静态检查，不会替代 Agent 监听、DNS 公网解析和真实客户端连通性测试。',
      });
    }

    return result;
  }, [snapshot]);

  const counts: Array<[string, number, string]> = [
    ['转发', snapshot.forwards.length, '/forward'],
    ['三网优化', snapshot.smartGroups.length, '/smart-entry'],
    ['来源 IP 分流', snapshot.sourceGroups.length, '/source-ip-entry'],
    ['入口容灾', snapshot.failoverGroups.length, '/cross-entry-failover'],
    ['并发调度', snapshot.aggregationGroups.length, '/multi-line-aggregation'],
  ];

  return (
    <div className="mx-auto w-full max-w-[1680px] space-y-6 p-4 sm:p-6">
      <header className="flex flex-col gap-4 border-b border-divider pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm text-default-500">线路调度</p>
          <h1 className="mt-1 text-2xl font-semibold">线路调度中心</h1>
          <p className="mt-2 max-w-4xl text-sm leading-6 text-default-500">
            统一查看每个调度模块负责什么、依赖什么，以及当前配置是否存在潜在的 DNS 或端口重叠。这里只读检查，不会自动修改线路。
          </p>
        </div>
        <div className="flex items-center gap-3">
          {refreshedAt && <span className="text-xs text-default-500">更新于 {new Date(refreshedAt).toLocaleTimeString('zh-CN', { hour12: false })}</span>}
          <Button isIconOnly variant="flat" aria-label="刷新调度总览" title="刷新调度总览" isLoading={loading} onPress={() => void load()}>
            <RefreshCw size={17} />
          </Button>
        </div>
      </header>

      {errors.length > 0 && (
        <section className="flex gap-3 border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
          <CircleHelp className="mt-0.5 h-5 w-5 flex-none" />
          <div>
            <p className="font-medium">部分检查未完成</p>
            <p className="mt-1 leading-5">{errors.join('；')}</p>
            <p className="mt-1 text-xs opacity-80">辅助接口失败不会阻塞其他模块；可以稍后单独刷新对应页面。</p>
          </div>
        </section>
      )}

      <section className="grid grid-cols-2 border-y border-divider sm:grid-cols-5" aria-label="线路调度配置数量">
        {counts.map(([label, value, path], index) => (
          <button
            key={label}
            type="button"
            onClick={() => navigate(path)}
            className={`flex min-h-24 items-center justify-between px-4 py-4 text-left transition-colors hover:bg-default-50 dark:hover:bg-default-100/5 sm:px-6 ${index < counts.length - 1 ? 'border-r border-divider' : ''}`}
          >
            <div><p className="text-xs text-default-500">{label}</p><p className="mt-1 text-2xl font-semibold">{loading ? '-' : value}</p></div>
            <ExternalLink className="h-4 w-4 text-default-400" />
          </button>
        ))}
      </section>

      <section>
        <div className="mb-3 flex items-end justify-between gap-3">
          <div><h2 className="text-base font-semibold">模块边界</h2><p className="mt-1 text-xs text-default-500">先确定谁负责“选路”，再配置对应的 DNS、统一入口或线路策略。</p></div>
          <Chip size="sm" variant="flat">只读说明</Chip>
        </div>
        <div className="grid gap-3 lg:grid-cols-2">
          {modules.map(item => {
            const Icon = item.icon;
            return (
              <article key={item.title} className="border border-divider bg-content1 p-4 sm:p-5">
                <div className="flex items-start gap-3">
                  <div className={`rounded-md p-2 ${toneClass[item.tone]}`}>
                    <Icon size={19} />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-semibold">{item.title}</h3>
                      <Chip size="sm" variant="flat" color={item.tone}>{item.role}</Chip>
                    </div>
                    <p className="mt-2 text-sm leading-6 text-default-600">{item.detail}</p>
                    <p className="mt-2 border-t border-divider pt-2 text-xs leading-5 text-default-500">{item.boundary}</p>
                    <div className="mt-3">{routePath(item.path, navigate)}</div>
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      </section>

      <section>
        <div className="mb-3 flex items-end justify-between gap-3">
          <div><h2 className="text-base font-semibold">功能能力矩阵</h2><p className="mt-1 text-xs text-default-500">用一张表确认版本要求、是否吃中转流量、是否依赖 DNS，以及能否给普通用户直接授权。</p></div>
          <Chip size="sm" variant="flat">调度关系</Chip>
        </div>
        <div className="overflow-x-auto border border-divider bg-content1">
          <table className="w-full min-w-[980px] text-left text-sm">
            <thead className="border-b border-divider bg-default-100 text-xs text-default-500">
              <tr>
                <th className="px-4 py-3 font-medium">功能</th>
                <th className="px-4 py-3 font-medium">Agent 版本要求</th>
                <th className="px-4 py-3 font-medium">是否产生中转流量</th>
                <th className="px-4 py-3 font-medium">是否依赖 DNS</th>
                <th className="px-4 py-3 font-medium">UDP 支持</th>
                <th className="px-4 py-3 font-medium">普通用户授权</th>
              </tr>
            </thead>
            <tbody>
              {capabilityRows.map(row => (
                <tr key={row.feature} className="border-b border-divider/70 last:border-0">
                  <td className="px-4 py-3 font-medium">{row.feature}</td>
                  <td className="px-4 py-3 text-default-600">{row.agent}</td>
                  <td className="px-4 py-3 text-default-600">{row.traffic}</td>
                  <td className="px-4 py-3 text-default-600">{row.dns}</td>
                  <td className="px-4 py-3 text-default-600">{row.udp}</td>
                  <td className="px-4 py-3 text-default-600">{row.userAuth}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <div className="mb-3 flex items-end justify-between gap-3">
          <div><h2 className="text-base font-semibold">静态检查结果</h2><p className="mt-1 text-xs text-default-500">提示项不会自动关闭功能，避免误伤现有线路。</p></div>
          <Chip size="sm" variant="flat">{findings.length} 项</Chip>
        </div>
        <div className="space-y-3">
          {findings.map((finding, index) => (
            <article key={`${finding.title}-${index}`} className={`flex gap-3 border px-4 py-4 ${finding.level === 'warning' ? 'border-warning-200 bg-warning-50/60 dark:border-warning-500/20 dark:bg-warning-500/10' : 'border-divider bg-content1'}`}>
              {finding.level === 'warning' ? <AlertTriangle className="mt-0.5 h-5 w-5 flex-none text-warning-600" /> : <CheckCircle2 className="mt-0.5 h-5 w-5 flex-none text-success" />}
              <div className="min-w-0 flex-1">
                <h3 className="font-medium">{finding.title}</h3>
                <p className="mt-1 text-sm leading-6 text-default-600">{finding.detail}</p>
                {finding.links && <div className="mt-3 flex flex-wrap gap-2">{finding.links.map(link => <Button key={`${link.path}-${link.label}`} size="sm" variant="flat" onPress={() => navigate(link.path)}>{link.label}</Button>)}</div>}
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="border-y border-divider py-4 text-xs leading-5 text-default-500">
        <p className="font-medium text-foreground">保存时会阻断明确冲突</p>
        <p className="mt-1">同一个 DNS 记录不能同时交给三网优化和入口容灾管理；完全相同的一组转发不能同时交给不同调度策略接管。页面里的静态提示用于提前发现风险，后端保存校验用于阻止新冲突落库。</p>
      </section>
    </div>
  );
}
