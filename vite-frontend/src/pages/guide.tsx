import { Accordion, AccordionItem } from "@heroui/accordion";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Tab, Tabs } from "@heroui/tabs";
import {
  Activity,
  ArrowRightLeft,
  ArrowRight,
  BellRing,
  BookOpen,
  Bot,
  Boxes,
  CloudCog,
  Combine,
  GitBranch,
  Globe2,
  Gauge,
  Home,
  House,
  KeyRound,
  LayoutDashboard,
  LockKeyhole,
  MapPinned,
  Network,
  RadioTower,
  RefreshCw,
  SearchCheck,
  Search,
  Server,
  Settings,
  ShieldCheck,
  SquareTerminal,
  Users,
  Waypoints,
  Wrench,
  type LucideIcon,
  Wallet,
} from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";

import { isAdmin } from "@/utils/auth";
import PageHelp from "@/components/page-help";

type GuideCategory = "core" | "access" | "tools" | "system";

interface GuideEntry {
  id: string;
  category: GuideCategory;
  title: string;
  path: string;
  icon: LucideIcon;
  adminOnly?: boolean;
  summary: string;
  purpose: string;
  prerequisites: string[];
  steps: string[];
  result: string;
  notes: string[];
  keywords?: string;
}

const categories: Array<{ key: "all" | GuideCategory; label: string }> = [
  { key: "all", label: "全部" },
  { key: "core", label: "核心业务" },
  { key: "access", label: "接入与发布" },
  { key: "tools", label: "实用工具" },
  { key: "system", label: "系统管理" },
];

const categoryLabels: Record<GuideCategory, string> = {
  core: "核心业务",
  access: "接入与发布",
  tools: "实用工具",
  system: "系统管理",
};

const entries: GuideEntry[] = [
  {
    id: "dashboard",
    category: "core",
    title: "仪表板",
    path: "/dashboard",
    icon: LayoutDashboard,
    summary: "查看整个面板的资源、流量和异常概况。",
    purpose:
      "仪表板是日常巡检入口，用来快速判断节点、隧道、转发和权限资源是否正常。它展示的是汇总结果，具体配置仍在对应管理页面完成。",
    prerequisites: ["登录面板即可使用；数据越完整，概况越有参考价值。"],
    steps: [
      "先查看异常、离线和告警数量。",
      "查看最近的线路变化、流量和用户资源使用情况。",
      "发现异常后，打开对应节点、隧道、转发或告警页面继续处理。",
    ],
    result: "不用逐页检查，也能先确认当前业务是否整体可用。",
    notes: [
      "普通用户只看到自己拥有或管理员共享的资源。",
      "卡片顺序可拖动，排序保存在当前账户。",
    ],
  },
  {
    id: "node",
    category: "core",
    title: "添加节点",
    path: "/node",
    icon: Server,
    summary: "把 VPS 或服务器接入 CloudNest，成为所有网络功能的基础资源。",
    purpose:
      "节点代表一台已安装 Agent 的服务器。面板依靠 Agent 获取在线状态、流量、延迟和系统信息，并远程下发隧道、转发、诊断及代理配置。没有节点，就没有可以承载公网入口或中转的服务器。",
    prerequisites: [
      "一台具有 root 或管理员权限的 Linux 服务器。",
      "服务器能访问面板地址与 GitHub 下载地址。",
      "安全组和系统防火墙允许 Agent 控制连接及后续业务端口。",
    ],
    steps: [
      "点击“添加节点”，填写名称、服务器 IP 和入口 IP；不区分时两项填写同一地址。",
      "保存节点后复制安装命令，在目标服务器以 root 身份执行。",
      "等待卡片显示在线，并核对 Agent 版本、开机时间和公网地址。",
      "需要维护时可打开节点终端、运行诊断或执行 Agent 在线升级。",
    ],
    result:
      "该服务器进入面板资源池，可用于组建隧道、承载转发、私人代理和内网映射入口。",
    notes: [
      "入口 IP 是用户实际连接的地址，服务器 IP 是 Agent 和节点识别地址。",
      "离线节点无法接收新配置；安装失败时使用卡片中的手动安装命令。",
      "管理员可以看到普通用户创建的节点及其所有者。",
    ],
  },
  {
    id: "tunnel",
    category: "core",
    title: "隧道组建",
    path: "/tunnel",
    icon: GitBranch,
    summary: "把两台或多台节点按顺序连接成一条可复用的网络路径。",
    purpose:
      "隧道解决“流量应该经过哪些服务器”的问题。例如 A → B 是普通隧道，A → B → C 是多级隧道。隧道本身定义路径，真正对外开放业务端口的是转发管理。",
    prerequisites: [
      "至少两台在线节点。",
      "链路中的节点能够互相访问所需端口。",
      "多级链路应提前确认每一跳的网络质量。",
    ],
    steps: [
      "点击新增，选择入口节点和出口节点。",
      "需要多级路径时依次添加中转节点，顺序就是实际流量顺序。",
      "选择监听地址、通信协议和端口，保存配置。",
      "等待隧道显示正常，并查看每一跳及总延迟。",
    ],
    result: "得到一条可在转发管理中选择的线路。多个转发可以复用同一条隧道。",
    notes: [
      "节点离线会使经过该节点的隧道异常。",
      "删除隧道会同时清理由它承载的转发，操作前应核对影响范围。",
      "级数较多不一定更快，每增加一跳都会增加延迟和故障点。",
    ],
  },
  {
    id: "forward",
    category: "core",
    title: "转发管理",
    path: "/forward",
    icon: Network,
    summary: "把公网入口端口经过指定线路送到目标地址和端口。",
    purpose:
      "转发解决“用户连接哪个地址，最终到达哪里”的问题。它把入口节点上的公网端口、隧道线路和目标服务组合成一条可使用的业务链路。",
    prerequisites: [
      "至少一条正常隧道。",
      "入口端口未被隧道、其他转发、代理或端口资源占用。",
      "目标地址和目标端口能够从出口节点访问。",
    ],
    steps: [
      "点击新增，填写名称并选择主线路。",
      "填写入口端口、目标地址、目标端口和 TCP/UDP 协议。",
      "按需要选择单线路、主备切换或低延迟选路，并添加候选线路。",
      "保存后检查卡片状态、总延迟、当前承载线路和切换记录。",
    ],
    result: "用户连接“入口 IP:入口端口”后，流量会沿隧道到达目标服务。",
    notes: [
      "同一转发的候选线路必须使用同一个入口节点，才能在不改变访问地址的情况下切换。",
      "不同入口节点之间的切换应使用“入口容灾”。",
      "IP 哈希适合让同一来源尽量固定到同一目标；主备适合故障切换，低延迟适合自动择优。",
    ],
  },
  {
    id: "routing-overview",
    category: "core",
    title: "线路调度中心",
    path: "/routing-overview",
    icon: Waypoints,
    adminOnly: true,
    summary:
      "集中查看转发、三网优化、来源 IP 分流、入口容灾和多线路并发调度的边界。",
    purpose:
      "当面板功能越来越多时，最容易出问题的不是单个功能，而是多个功能同时接管同一个域名、端口或入口。线路调度中心展示每种调度模式的真实作用、能力矩阵和潜在重叠，后端保存时也会阻断明确冲突。",
    prerequisites: ["登录管理员账号。", "已有线路调度配置时信息更完整。"],
    steps: [
      "打开页面后查看每个模块的“职责”和“边界”。",
      "检查静态提示里是否有同一 DNS 记录或同一端口被多个模块使用。",
      "需要处理时点击提示里的入口，回到具体功能页修改配置。",
    ],
    result:
      "先判断应该用哪一种选路方式，再进入具体页面配置，减少功能之间互相抢占。",
    notes: [
      "总览页面本身只读，不会删除、不暂停、不修改任何线路。",
      "端口和 DNS 提示属于静态检查，真实可用性仍要结合 Agent 监听、DNS 公网解析和客户端测试。",
      "同一 DNS 记录不能同时交给三网优化和入口容灾；完全相同的一组转发不能同时交给多个调度策略。",
    ],
    keywords: "线路调度 冲突 总览 DNS 端口 来源IP 入口容灾",
  },
  {
    id: "nft-forward",
    category: "core",
    title: "nftables 端口转发",
    path: "/nft-forward",
    icon: ArrowRightLeft,
    adminOnly: true,
    summary: "直接使用 Linux 内核 DNAT/SNAT 转发端口，与 GOST 转发分开管理。",
    purpose:
      "nftables 转发适合不需要隧道、加密或应用层代理的普通 IPv4 端口映射。数据由 Linux 内核直接处理，路径短、开销低；GOST 仍负责跨节点隧道、加密协议和复杂转发，两者不会自动互相迁移。",
    prerequisites: [
      "执行节点必须是 Linux，并安装 nftables。",
      "Agent 版本达到页面显示的最低版本且在线。",
      "目标必须是固定 IPv4；入口端口不能被 GOST、其他面板资源、真实 socket 或外部 DNAT 占用。",
    ],
    steps: [
      "新建规则并选择执行节点、监听地址、入口端口及 TCP/UDP。",
      "填写目标 IPv4 和端口；通常选择“标准 NAT”。",
      "需要限制来源时填写 IPv4/CIDR 白名单。",
      "先运行环境预检，再保存并应用；创建后用“检查状态”刷新计数器和目标连通性。",
      "变更不合适时点击回退按钮恢复上次成功配置；暂停和删除会经过 Agent 对账。",
    ],
    result:
      "客户端连接“节点公网 IP:入口端口”后，由内核直接转发到目标 IPv4 和端口。",
    notes: [
      "该功能只管理 table ip cloudnest_nat，绝不会执行 flush ruleset。",
      "标准 NAT 会隐藏客户端来源 IP；保留来源 IP 要求目标主机把回程流量交回执行节点。",
      "Agent 离线时不会假装删除成功，规则和端口账本会保留，节点恢复后可重试。",
      "第一版不支持域名目标、IPv6、限速或多目标负载均衡。",
    ],
    keywords: "nft DNAT SNAT MASQUERADE 内核转发 端口映射 回退",
  },
  {
    id: "smart-entry",
    category: "core",
    title: "三网优化",
    path: "/smart-entry",
    icon: Waypoints,
    adminOnly: true,
    summary: "客户只填一个域名和端口，电信、联通、移动自动解析到各自的入口。",
    purpose:
      "由阿里云 DNS 或 DNSPod 按来源运营商返回对应入口，手机 5G、家宽等远程用户都适用，客户端无需安装程序或修改 DNS。业务连接直达入口。",
    prerequisites: [
      "一个托管在阿里云 DNS 或 DNSPod 的域名，并已在资源中心保存对应的 DNS 账号。",
      "已经创建至少两条使用不同入口节点、相同公网端口的正常转发。",
    ],
    steps: [
      "点击“新建三网优化”，填写完整业务域名，面板会自动匹配托管它的 DNS 账号和主域名。",
      "选择默认入口，再分别选择电信、联通和移动入口；留空的线路使用默认入口。",
      "保存后等待约 1 分钟，卡片状态变为“正常”，并自动显示三网解析验证结果。",
      "把卡片上“客户端填写”的地址和端口交给客户即可。",
    ],
    result: "不同运营商发起的新连接可解析到各自更合适的公网入口。",
    notes: [
      "这不是根据持续流量把单个用户来回迁移，而是在 DNS 查询阶段分配入口。",
      "客户切换网络后要等 DNS 缓存过期才会换入口，时间取决于“切换生效时间”；已有连接不会迁移。",
      "客户端使用第三方加密 DNS 时可能识别不出运营商，此时走默认入口。",
      "OpenWrt 本地选路已不再提供新建。已有的本地策略继续运行，可在编辑页改为公共 DNS，或直接删除。",
    ],
    keywords: "移动 联通 电信 运营商 DNS 线路解析",
  },
  {
    id: "source-ip-entry",
    category: "core",
    title: "来源 IP 分流",
    path: "/source-ip-entry",
    icon: RadioTower,
    adminOnly: true,
    summary:
      "由统一 TCP 入口按真实客户端来源 IP、CIDR、ASN、地区或自定义规则选择后端线路。",
    purpose:
      "来源 IP 分流存在的原因，是 DNS 缓存、私人 DNS、公共 DNS 和客户端缓存可能导致三网优化选错入口。它不依赖客户端 DNS，而是在入口 Agent 收到连接时根据真实来源 IP 做最长匹配或规则匹配，再把 TCP 流量交给对应后端转发。它适合固定入口域名不想换、但又希望不同来源走不同后端线路的场景。",
    prerequisites: [
      "一台在线且版本满足要求的统一入口节点。",
      "已经创建多条后端转发，并且这些后端使用相同上层协议和正确目标端口。",
      "如果使用运营商或 ASN 规则，需要先在页面刷新运营商/ASN 数据库；缓存优先，缺失时再联网补充。",
    ],
    steps: [
      "新建分流组，选择统一入口节点、监听地址和监听端口。",
      "添加默认回退规则，保证未命中的来源也有可走线路。",
      "按需要添加运营商、CIDR/IP 段、ASN、地区、VIP 来源、专属客户、灰度测试或风险隔离规则。",
      "为每条规则选择后端转发，并设置优先级；数字越小越靠前，CIDR 规则仍按最长匹配优先。",
      "保存后使用“调试来源 IP”输入真实客户端 IP，确认命中了哪条规则和后端线路。",
    ],
    result:
      "用户仍连接同一个域名或同一个入口端口，但入口 Agent 会按来源规则把连接送到不同后端线路。",
    notes: [
      "它只做 TCP 入口层转发，不解密 VLESS、REALITY、Trojan 等上层协议，也不会修改协议参数。",
      "它会让统一入口机器产生中转流量；如果不想让任何统一入口承担流量，只能使用 DNS、客户端本地选择或不同域名入口。",
      "ASN 和地区数据库适合作为辅助判断，不能百分百替代真实运营商探测；关键客户建议用明确 CIDR 或专属客户规则。",
      "不要让同一个端口同时被三网优化、入口容灾和来源 IP 分流接管，先到线路调度中心看冲突提示。",
    ],
    keywords:
      "来源IP 分流 CIDR ASN 地区 VIP 客户 灰度 风险隔离 DNS缓存 统一入口",
  },
  {
    id: "entry-failover",
    category: "core",
    title: "入口容灾",
    path: "/cross-entry-failover",
    icon: ShieldCheck,
    adminOnly: true,
    summary: "主公网入口失效时，把业务域名自动切换到备用入口。",
    purpose:
      "普通转发容灾只能在同一个入口节点下切换隧道；入口容灾处理的是入口 VPS 本身离线、端口不可达或整台服务器失效。面板持续探测入口端口，达到失败阈值后修改 DNS，让新连接进入备用 VPS。",
    prerequisites: [
      "资源中心已登记 Cloudflare API Token 和 Zone。",
      "至少两条入口节点不同、对外端口相同的正常转发。",
      "业务使用域名访问，而不是把固定 IP 写死在客户端。",
    ],
    steps: [
      "点击“新建容灾组”，选择 Cloudflare Zone，填写业务域名并选择 A 或 AAAA。",
      "第一条转发作为主入口，继续添加一条或多条备用入口。",
      "选择快速、均衡、稳定或自定义检测参数，并决定恢复后是否自动回切。",
      "保存并同步 DNS；通过“立即检测”和“切换历史”核对运行情况。",
    ],
    result:
      "主入口连续检测失败后，DNS 自动指向健康备用入口；主入口恢复后可按设置回切。",
    notes: [
      "检测和 DNS 更新可以很快，但运营商及客户端缓存决定最终生效时间。",
      "已经建立的 TCP 连接不会无感搬迁，通常需要重新连接。",
      "三网优化解决“不同运营商走不同入口”，入口容灾解决“当前入口坏了换备用”，两者目的不同。",
    ],
    keywords: "高可用 Cloudflare DNS 主备 跨入口 故障切换",
  },
  {
    id: "multi-line-aggregation",
    category: "core",
    title: "多线路并发调度",
    path: "/multi-line-aggregation",
    icon: Combine,
    adminOnly: true,
    summary: "把多个已存在转发组成一个入口组，按连接级别分配到不同线路。",
    purpose:
      "这个功能不是把同一个 TCP 单连接拆成多条线路叠加带宽，而是把新建连接按权重、速度优先、轮询或哈希分配到不同转发。它适合 YouTube、下载器、多线程测速、网页并发请求这类天然会创建多个连接的业务，用来提高整体吞吐或降低单条线路拥塞风险。",
    prerequisites: [
      "已经有多条健康转发，且这些转发监听端口真实可用。",
      "同一个并发调度组的所有候选线路必须使用相同入口节点。",
      "候选线路的目标业务和协议应一致，避免同一个客户端连接到不同服务。",
    ],
    steps: [
      "新建调度组，选择入口节点、监听端口和协议模式。",
      "添加候选转发，填写权重、带宽参考值和健康检查参数。",
      "选择调度策略：速度优先适合尽量挑快线路，轮询适合平均分配，源 IP 哈希适合让同一来源更稳定。",
      "保存后检查每条成员线路是否健康；底层转发端口未监听时，先回到转发管理修复。",
      "用多连接场景测试效果，不要只拿单线程测速结果判断它是否有效。",
    ],
    result:
      "同一个入口地址可以把不同连接分散到多条线路，在多连接业务下提高综合体验。",
    notes: [
      "单线程测速、单个长 TCP 连接、某些游戏或实时会话通常不会明显变快。",
      "如果底层线路本身不通或端口没监听，调度组不会自动帮你部署底层转发。",
      "延迟差异过大的线路混在一起，可能让体验变得忽快忽慢；应先用质量实验室或真实带宽测试筛选成员。",
    ],
    keywords: "多线路 并发调度 聚合 轮询 权重 哈希 多连接 YouTube",
  },
  {
    id: "port-resources",
    category: "access",
    title: "端口资源",
    path: "/port-resources",
    icon: Boxes,
    adminOnly: true,
    summary: "资源中心统一管理端口、DNS、DDNS、AWS 和家庭设备入口。",
    purpose:
      "端口是转发、内网映射、代理和家庭出口共同使用的全局资源。资源中心把端口资源、家庭设备、域名管理、动态解析和 AWS 凭据放在同一个入口；端口资源页面继续用端口池管理可用范围，并通过全局账本记录占用、租期、用途和归属。",
    prerequisites: ["至少一台在线节点。", "预先确定服务器允许开放的端口范围。"],
    steps: [
      "新建端口池，选择服务器并填写公网地址、绑定地址和端口范围。",
      "通过占用明细检查每个端口当前被什么业务使用。",
      "需要交给普通用户时，在用户管理中分享指定端口范围。",
      "出现冲突时运行端口冲突诊断，同时核对系统真实监听端口。",
    ],
    result:
      "所有依赖端口的功能都从同一账本分配资源，已占用端口不能被重复使用或分享。",
    notes: [
      "分享给用户的端口在分享期间管理员也不能再次使用。",
      "删除端口池前必须先处理依赖它的业务。",
      "面板账本空闲不等于操作系统端口一定空闲，新版 Agent 会进行真实端口检查。",
    ],
  },
  {
    id: "private-network",
    category: "access",
    title: "内网组建与出口",
    path: "/private-network",
    icon: Waypoints,
    adminOnly: true,
    summary: "把原生内网、Agent 自动组网和出口应用放在同一个页面管理。",
    purpose:
      "内网组建与出口解决“哪些机器可以走内网、如何验证内网、如何把内网路径应用到代理出口”的问题。虚拟局域网已经并入这里，适合没有同云内网但需要用 Agent 组成 WireGuard 虚拟网段的场景。",
    prerequisites: [
      "至少两台在线节点，或一台公网中继节点加一个 Linux Connector。",
      "自动组网需要对应 Agent 版本支持。",
      "涉及公网中继时需要放行 WireGuard UDP 端口。",
    ],
    steps: [
      "先在“原生内网”登记云 VPC、云骨干或专线内网，并执行双向验证。",
      "没有原生内网时，在“Agent 自动组网”创建虚拟局域网。",
      "需要本地连接 B、C 出口或继续添加 D 出口时，在“代理出口应用”选择节点路径和每跳地址模式。",
      "创建后用测试按钮确认入口、内网跳点和出口都可达。",
    ],
    result:
      "可以明确知道每一跳走公网、原生内网还是 Agent 虚拟内网，并把这条路径发布为可用代理入口。",
    notes: [
      "Agent 虚拟局域网会经过中继节点，和云厂商原生内网不是同一种能力。",
      "如果 B 和 C 有真实同地域内网，优先登记原生内网并验证，成本和延迟通常更好。",
      "旧的“虚拟局域网”地址会跳转到本页。",
    ],
    keywords: "虚拟局域网 内网组建 WireGuard 私网 出口应用",
  },
  {
    id: "home-devices",
    category: "access",
    title: "家庭设备",
    path: "/home-devices",
    icon: SquareTerminal,
    summary: "把家庭电脑、软路由或内网主机作为主动接入端连接面板。",
    purpose:
      "家庭设备通常没有稳定公网 IPv4。安装接入端 Agent 后，设备主动连接面板，因此可以被内网映射、动态 DNS 和家庭网络中转使用。",
    prerequisites: [
      "Windows、macOS 或 Linux 设备。",
      "设备能够主动访问面板。",
      "执行安装服务所需的管理员权限。",
    ],
    steps: [
      "在资源中心打开“家庭设备”，新建设备并选择操作系统。",
      "复制对应安装命令，在设备上以管理员权限执行。",
      "等待状态变为在线并核对 Agent 版本和最近地址。",
      "不再使用时先删除关联业务，再执行页面提供的卸载命令。",
    ],
    result:
      "内网设备成为持续在线的接入端，但它与公网 VPS 节点仍是两类不同资源。",
    notes: [
      "Windows 和 macOS 应使用各自安装命令，不能直接执行 Linux 脚本。",
      "设备休眠、关机或网络中断会使依赖它的服务暂时不可用。",
    ],
  },
  {
    id: "dns-settings",
    category: "access",
    title: "域名管理",
    path: "/dns-settings",
    icon: Globe2,
    adminOnly: true,
    summary: "集中保存 DNS 服务商凭据和域名区域，供其他功能复用。",
    purpose:
      "这里统一管理 Cloudflare、DNSPod 和阿里云 DNS 的访问凭据。入口容灾、三网优化、动态 DNS、托管 HTTPS 都从这里选择已有配置，避免每次重复填写密钥。",
    prerequisites: [
      "域名已经添加到对应 DNS 服务商。",
      "准备权限最小化的 API Token、Secret 或 AccessKey。",
    ],
    steps: [
      "按服务商新增账号配置并填写凭据。",
      "Cloudflare 配置同步 Zone；DNSPod 和阿里云配置用于线路解析与动态 DNS。",
      "核对主域名当前权威 DNS 与所选服务商一致。",
      "随后在三网优化、入口容灾、动态解析或内网映射中直接选择。",
    ],
    result: "面板可以自动创建记录、更新解析并完成 DNS-01 证书验证。",
    notes: [
      "API 密钥属于敏感信息，应只授予所需域名和 DNS 编辑权限。",
      "把域名保留在原 DNS 服务商却选择另一个服务商的凭据不会生效。",
    ],
  },
  {
    id: "aws-access",
    category: "access",
    title: "AWS 资源",
    path: "/aws-access",
    icon: CloudCog,
    adminOnly: true,
    summary:
      "集中保存 AWS Access Key，用于后续 CloudFront、ACM 或相关云资源自动化。",
    purpose:
      "AWS 资源页的存在，是为了把云厂商密钥从具体业务页面中抽出来统一管理。后续需要创建或验证 CloudFront XHTTP 分离、ACM 证书、分配域名等能力时，可以复用这里的账号，不需要在每条业务规则里重复保存 AK/SK。",
    prerequisites: [
      "已在 AWS IAM 中创建 Access Key。",
      "只授予所需服务的最小权限，例如 CloudFront、ACM、Route53 读取或变更权限。",
      "如果涉及 CloudFront 证书，通常需要关注 us-east-1 区域的 ACM 证书要求。",
    ],
    steps: [
      "打开 AWS 资源，新增账号名称、Access Key ID、Secret Access Key 和默认 Region。",
      "保存后点击验证，确认面板服务器能访问 AWS API 并读取到账号信息。",
      "验证成功后，在需要 AWS 能力的页面选择该账号。",
      "密钥轮换时编辑账号并重新填写 Secret；不再使用时先确认无业务依赖再删除。",
    ],
    result:
      "CloudNest 获得可复用的 AWS 自动化入口，后续功能可以直接选择账号执行云资源操作。",
    notes: [
      "AK/SK 会加密保存，页面不会回显 Secret 明文。",
      "不要使用主账号长期密钥；建议为面板单独创建 IAM 用户或角色并限制权限。",
      "保存 AWS 凭据本身不会自动创建 CloudFront、证书或 DNS 记录，只有具体业务提交后才会执行。",
    ],
    keywords: "AWS AK SK CloudFront ACM Route53 us-east-1 资源中心",
  },
  {
    id: "dynamic-dns",
    category: "access",
    title: "动态解析",
    path: "/dynamic-dns",
    icon: RefreshCw,
    adminOnly: true,
    summary: "节点或家庭公网 IP 变化后，自动更新域名的 A/AAAA 记录。",
    purpose:
      "动态 DNS 适合家庭宽带和公网地址会变化的服务器。Agent 定期获取指定设备的公网 IPv4 或 IPv6，只有地址发生变化时才更新 DNS。",
    prerequisites: [
      "已保存 Cloudflare、DNSPod 或阿里云 DNS 配置。",
      "检测来源节点或家庭设备在线且 Agent 版本满足要求。",
    ],
    steps: [
      "新建规则，选择检测来源是服务器节点还是家庭设备。",
      "选择 DNS 配置、主域名、记录名称以及 A 或 AAAA。",
      "设置 TTL 和检测间隔后启用规则。",
      "查看当前 IP、上次检查、更新历史和错误信息。",
    ],
    result: "客户端使用固定域名即可访问地址不断变化的公网设备。",
    notes: [
      "家庭还没有公网 IPv4 时，A 记录可能检测到上级 NAT 或错误出口，不能用于入站访问。",
      "AAAA 记录需要家庭获得可入站的公网 IPv6，并在路由器和系统防火墙放行端口。",
    ],
  },
  {
    id: "publishing",
    category: "access",
    title: "内网映射",
    path: "/service-publishing",
    icon: RadioTower,
    summary: "把家庭或公司内网服务发布到公网端口或 HTTPS 域名。",
    purpose:
      "内网映射把无法直接从公网访问的 Web、SSH、RDP、游戏或其他 TCP 服务，通过在线接入端和公网节点开放出去。服务模板会预填常用端口，但不会替代目标设备自身的安全配置。",
    prerequisites: [
      "一个在线的内网接入端。",
      "管理员提供的端口资源，或普通用户获得的共享端口。",
      "目标服务在接入端所在网络内可访问。",
    ],
    steps: [
      "先在“内网接入端”页签添加设备并完成 Agent 安装。",
      "在“映射列表”新建映射，选择模板或自定义目标地址和端口。",
      "选择端口资源，并设置永久或按小时到期。",
      "需要域名时，再到“域名入口”选择后端映射；托管 HTTPS 会自动申请证书，TLS 透传则由内网服务管理证书。",
    ],
    result: "得到公网“地址:端口”或 HTTPS 域名，外部连接会被送到指定内网服务。",
    notes: [
      "托管 HTTPS 支持同一 443 端口按域名和路径分流。",
      "公开 SSH、RDP 和数据库时应设置来源限制、强密码或密钥认证。",
      "TCP 映射与 UDP 能力应以创建界面实际提供的协议为准。",
    ],
  },
  {
    id: "docker-apps",
    category: "access",
    title: "Docker 应用中心",
    path: "/docker-apps",
    icon: Boxes,
    adminOnly: true,
    summary: "识别节点 Docker 环境，并一键部署、管理和发布常用容器应用。",
    purpose:
      "Docker 应用中心让 CloudNest 不只管理网络链路，也能把 X-UI、哪吒、Alist、Nextcloud 等服务快速部署到节点上。它统一处理容器创建、端口分配、域名绑定、反代发布、启动停止、升级和删除，减少你反复登录服务器敲命令的成本。",
    prerequisites: [
      "目标节点在线，Agent 版本达到页面显示的最低要求。",
      "节点已安装 Docker，或至少允许按页面命令安装 Docker。",
      "需要绑定域名时，资源中心已配置 Cloudflare DNS，并准备可用 HTTPS 入口节点。",
    ],
    steps: [
      "先点击节点检测，确认 Docker 是否可用、Docker 版本和当前容器列表。",
      "点击新建应用，选择模板、节点、应用名、容器名和宿主机端口。",
      "需要公网域名时开启绑定域名，选择 DNS Zone、入口节点、监听端口和路径。",
      "创建后在应用卡片执行启动、停止、重启、升级、备份或删除，并查看事件日志。",
      "部署失败时打开命令窗口查看面板准备的等效 Docker 命令，方便手动排查。",
    ],
    result:
      "常用自托管应用可以从面板直接部署并发布，不必把 Docker、端口、域名和反代分散到多个地方维护。",
    notes: [
      "应用中心管理的是容器应用，不等同于代理协议；代理仍在私人代理、转发或隧道功能中创建。",
      "端口仍受全局端口账本约束，已被转发、代理或其他应用占用时不能重复使用。",
      "删除应用前确认数据卷和备份策略；容器删除不一定等于业务数据已经安全备份。",
    ],
    keywords: "Docker 应用中心 x-ui 哪吒 Alist Nextcloud 反代 域名 容器",
  },
  {
    id: "home-access",
    category: "access",
    title: "家庭网络中转",
    path: "/home-access",
    icon: Home,
    summary: "让公司或移动设备先连接家庭网络，再从指定服务器或隧道访问目标。",
    purpose:
      "该功能建立“客户端 → 家庭设备 → 指定服务器/出口隧道”的路径。公司到家庭使用 SOCKS5；家庭到首个出口可以继续使用轻量 SOCKS5，也可以使用 VLESS + REALITY 保护跨境首跳。IPv6/IPv4 直连要求家庭具备可入站公网地址；公网中继用于兼容无法直连的环境。",
    prerequisites: [
      "一个在线家庭设备。",
      "直连模式需要公网 IPv6 或公网 IPv4、对应 A/AAAA 动态解析规则以及正确防火墙规则。",
      "已经准备出口端口资源或出口隧道。",
    ],
    steps: [
      "点击“新建中转”，选择家庭接入端。",
      "选择 IPv6 直连、IPv4 直连或公网中继，并填写监听端口。",
      "直连模式必须绑定该家庭设备的 DDNS：IPv4 选 A 记录，IPv6 选 AAAA 记录。",
      "选择“指定服务器出口”或已有隧道；指定服务器不再要求预先创建出口端口池。",
      "选择家庭到出口协议：境内或可信链路可用 SOCKS5，家庭连接海外服务器建议用 VLESS + REALITY。",
      "按需开启客户端认证，确认链路预览后创建。",
    ],
    result:
      "客户端连接家庭 SOCKS5 入口后，流量先到家庭网络，再沿所选出口路径访问互联网。",
    notes: [
      "当前家庭到普通出口网关使用 SOCKS5 over TCP；用户名密码用于认证，不等于 TLS 或 REALITY 加密。",
      "公网直连不再允许长期写死裸 IPv6 或 IPv4；家庭地址变化由绑定的 DDNS 规则更新。",
      "移动网络到家庭 IPv6 的跨网质量可能与 Wi-Fi 测试结果不同。",
    ],
  },
  {
    id: "private-proxy",
    category: "tools",
    title: "私人代理",
    path: "/private-proxy",
    icon: LockKeyhole,
    summary: "在指定服务器创建八种独立代理协议，并按节点集中管理。",
    purpose:
      "私人代理不依赖隧道或转发，是直接运行在单台节点上的独立访问服务。适合为个人设备提供固定代理入口。",
    prerequisites: [
      "一台在线节点。",
      "一个未被全局端口账本占用的监听端口。",
      "客户端支持准备使用的协议。",
    ],
    steps: [
      "点击“新建代理”，从通用代理、加密代理、QUIC 加速或设备组网中选择协议和服务器节点。",
      "填写监听端口、认证信息和有效期；支持的协议可再配置来源白名单。",
      "VLESS + REALITY 选择已验证伪装站；Shadowsocks 选择加密方式；Hysteria2、TUIC 和 WireGuard 需要放行 UDP。",
      "创建后打开连接信息，复制参数、一键导入链接或 WireGuard 配置到客户端。",
    ],
    result: "服务器直接提供一个可暂停、恢复和到期释放的私人代理服务。",
    notes: [
      "SOCKS5/HTTP 的账号密码主要用于认证，不提供 Reality 级别的链路特征处理。",
      "REALITY 自定义站点并非只要支持 HTTPS 就一定兼容，创建后必须实际连接验证。",
      "来源白名单留空代表允许任意来源，应根据暴露风险决定。",
    ],
  },
  {
    id: "exit-pool",
    category: "tools",
    title: "家宽出口",
    path: "/exit-pool",
    icon: House,
    summary: "让私人代理的出口变成指定国家的家庭宽带 IP，并自动保持可用。",
    purpose:
      "私人代理默认从节点自身的 IP 出去，网站看到的是机房地址。打开家宽出口后，节点会连接一个 VPN Gate 志愿者的家庭宽带，网站看到的就是该国家的家宽 IP。出口失效时系统自动换另一个。",
    prerequisites: [
      "一台 Linux 节点，Agent 版本 2.57.0 或更高，并支持 TUN 虚拟网卡。",
      "节点离目标国家越近越好，例如用日本出口就选日本或东亚的节点。",
      "除 WireGuard 外的协议都可以使用。VLESS + REALITY、Trojan、Hysteria2、TUIC 需要 Agent 2.58.1 或更高。",
    ],
    steps: [
      "在“私人代理”新建代理，把“出口”选成“指定国家的家宽”，再选国家。国家旁边的数字是当前可用的候选数量。",
      "创建后等十几秒，代理下方出现出口 IP 和运营商，就可以像普通私人代理一样使用，设备上填的仍是节点的地址和端口。",
      "已有的代理点房子图标可以修改出口，也可以随时改回“节点自身 IP”。",
      "在“出口管理 → 家宽出口”查看每个出口的状态和切换记录；可以点“换一个出口”、直接切到某个备用出口，或用“指定出口”自己选一个，某个出口表现不好时也可以手动排除。",
      "想在外面快速换 IP：点代理的房子图标，在“远程切换链接”里显示并复制链接，存成书签或快捷指令，打开一次就换一个出口。",
    ],
    result:
      "网站看到的是所选国家的家庭宽带 IP。出口全部不可用时代理会断开，不会改用节点自身 IP。",
    notes: [
      "可选国家由志愿者决定，目前以日本、韩国为主，其他国家时有时无。",
      "出口由多人共用：速度不稳定，更换出口时会断开几秒，出口 IP 也会变。",
      "出口列表是公开的，一部分地址已被 IP 库标记为代理，对代理敏感的网站仍可能识别。",
      "志愿者能看到未加密的流量并保留连接记录，请不要用它传输明文的敏感内容。",
      "首次使用时节点会自动安装 OpenVPN。节点的路由表和其他转发不受影响。",
    ],
  },
  {
    id: "mysterium",
    category: "tools",
    title: "Mysterium 家宽（付费）",
    path: "/exit-pool",
    icon: Wallet,
    adminOnly: true,
    summary:
      "按流量付费的家庭宽带出口，覆盖一百多个国家，可指定国家，花费有上限。",
    purpose:
      "Mysterium 是一个去中心化网络，出口由世界各地的个人用自己的家庭宽带运营。和免费的 VPN Gate 相比，可选国家多（美国、英国、日本、香港等），速度和稳定性更好，但按流量收费。费用从你自己节点上的 Mysterium 账户余额里扣，每个面板、每台节点的账户都是独立的：别人安装了这个面板，用的也是他们自己的账户和钱。",
    prerequisites: [
      "面板 2.83.0 或更高；要用的节点 Agent 2.83.0 或更高，最好是海外节点（需要能访问 GitHub 和 Mysterium 的服务器）。",
      "一个能付款的方式：PayPal、Visa/Mastercard 信用卡，或加密货币。",
      "只有管理员可以启用和使用，因为花的是管理员的钱。",
    ],
    steps: [
      "启用：打开“出口管理”，切到“Mysterium 家宽”标签页，勾选同意 Mysterium 用户条款，填写每台节点的每月预算（默认 20 美元），点“启用 Mysterium 家宽”。",
      "开账户：在“节点账户”里选一台节点，点“在节点上启用”。节点会自动安装 Mysterium 并生成一个余额为 0 的新账户，第一次约需 1～2 分钟。",
      "充值：点这台节点卡片上的“充值”，选付款方式、填金额（最低 6.3 美元），点“创建付款链接”，再点“打开付款页”，在 PayPal、Stripe 或 CoinGate 的官方页面付款。一般 5～10 分钟到账，点卡片右上角的刷新按钮可查看余额。",
      "使用：到“私人代理”，打开代理的“出口与分流”，在规则的出口或最下面的“其余流量”里选“Mysterium 家宽 · 国家”，保存。节点会自动连接该国质量最好的家宽，顶部“现在的效果”会显示哪些流量走它。",
      "续费：这是预充值，不是订阅，不会自动扣款。余额快用完时，在同一张卡片上再点“充值”付一次款即可，余额会累加。",
      "查看花费：节点卡片上显示余额（MYST 和约合美元）、本月已花和预算进度条、按余额估算的可用流量，以及每个国家连接的出口 IP、运营商、延迟和已用流量。页面下方的“可用国家”列出各国节点数和每 GB 价格。",
    ],
    result:
      "命中规则（或全部流量）从所选国家的真实家庭宽带出去，按实际流量扣费，本月花费超过预算时自动暂停。",
    notes: [
      "费用约每 GB 0.40～0.45 美元，上传和下载都计费；用 PayPal、信用卡充值另有手续费。建议只让需要的网站走它，或者给代理设流量上限。",
      "每台节点是独立账户，需要分别充值，余额不能在节点之间转移。",
      "本月花费达到预算后，这台节点的 Mysterium 自动暂停；提高预算或到下个月 1 号会自动恢复。余额低于“余额提醒”额度（默认 10 MYST）、超预算暂停、连接一直失败时，告警中心和 Telegram 会提醒。",
      "余额用完或连接失败时，走 Mysterium 的那部分流量会断开，其余流量不受影响；连不通时面板会自动换同国家的另一个家宽。",
      "只支持 TCP。QUIC、语音、游戏等 UDP 流量走不了这个出口。",
      "账户的钥匙文件只保存在节点上（Agent 目录下的 mysterium/data）。请保护好节点的 root 权限；删除这个目录会丢失余额。",
      "付款在 PayPal、Stripe、CoinGate 的官方页面完成，面板不经手任何付款信息；付款记录可在对应账单里查看。",
    ],
    keywords: "mysterium myst 家宽 住宅 residential 充值 续费 付费 出口",
  },
  {
    id: "network-tools",
    category: "tools",
    title: "网络诊断",
    path: "/network-tools",
    icon: Wrench,
    adminOnly: true,
    summary: "从指定节点执行 Ping、TCP、DNS 和路由跟踪。",
    purpose:
      "当某条链路异常时，网络诊断可以区分目标不可达、端口拒绝、DNS 错误和路由绕行，不必先登录服务器手动执行命令。",
    prerequisites: [
      "至少一台在线且版本受支持的节点。",
      "目标允许相应探测；部分服务器会禁用 ICMP。",
    ],
    steps: [
      "选择执行节点和 Ping、TCP、DNS 或路由模式。",
      "填写目标地址；TCP 模式填写端口，DNS 模式选择记录类型。",
      "设置探测次数和超时，点击开始诊断。",
      "结合摘要与原始输出判断故障发生位置。",
    ],
    result: "获得该节点视角下的实时网络检测结果。",
    notes: [
      "Ping 不通不代表 TCP 服务一定不可用，应继续做 TCP 检测。",
      "面板服务器视角与节点视角不同，入口容灾当前使用面板侧探测。",
    ],
  },
  {
    id: "quality-lab",
    category: "tools",
    title: "网络质量实验室",
    path: "/quality-lab",
    icon: Activity,
    adminOnly: true,
    summary: "使用现有 Agent 长期记录 TCP、TLS、TTFB、分位延迟、抖动和失败率。",
    purpose:
      "一次诊断只能说明当下是否连通。质量实验室将多轮样本按时间保存，用 P50、P95、P99、抖动和失败率判断线路在晚高峰及不同协议族下是否稳定。",
    prerequisites: [
      "执行节点 Agent 2.36.0 或更高版本。",
      "两台节点互测时，目标端口必须真实监听并允许来源节点访问。",
    ],
    steps: [
      "新建质量任务，选择执行节点和任意目标，或选择另一台节点互测。",
      "选择 TCP、TLS、HTTP 或 HTTPS，并设置 IPv4、IPv6或自动选择。",
      "先保持自动探测关闭并运行一次，确认目标、端口和 TLS 域名正确。",
      "确认后启用自动探测，在质量详情中查看趋势、时段和线路对比并下载报告。",
    ],
    result:
      "形成可按时间、协议族和来源线路比较的长期质量画像，并可下载 Markdown 报告。",
    notes: [
      "任务默认关闭，不创建或不启用任务就不会产生后台探测流量。",
      "运营商对比依据服务器资产中填写的线路标签，不等同于真实家庭电信、联通或移动探针。",
      "删除质量任务只删除质量历史，不会修改节点、隧道、转发、DNS 或端口。",
    ],
  },
  {
    id: "bandwidth-test",
    category: "tools",
    title: "真实带宽测试",
    path: "/bandwidth-test",
    icon: Gauge,
    adminOnly: true,
    summary:
      "让两个节点或节点到目标之间执行 TCP/UDP 吞吐、RTT、抖动、丢包和重传测试。",
    purpose:
      "真实带宽测试用于回答“这两台服务器之间到底能跑多少”“UDP 有没有丢包”“多并发是否比单并发更快”。它比普通 Ping 更接近实际转发承载能力，适合上线新线路前做验收，也适合排查家庭中转、跨境中转和落地出口速度不达标的问题。",
    prerequisites: [
      "至少一台在线节点；节点间互测时两端 Agent 都必须在线。",
      "测试端口需要能被临时监听和访问，云厂商安全组与系统防火墙不能阻断。",
      "大流量测试会真实消耗服务器流量，尤其是跨境和按量计费线路。",
    ],
    steps: [
      "新建任务，选择执行节点和目标节点或目标地址。",
      "选择 TCP 或 UDP、测试方向、持续时间、并发流数和最大带宽。",
      "先用小并发短时间测试确认连通，再提高时长和并发测上限。",
      "查看吞吐、RTT、抖动、丢包、乱序、重传和历史结果。",
      "将结果与质量实验室长期数据结合，判断是瞬时拥塞还是长期线路问题。",
    ],
    result:
      "获得接近真实业务链路的吞吐和丢包数据，帮助决定线路是否适合做入口、中转或出口。",
    notes: [
      "并发流数表示同时开多少条测试连接；单线程体验看 1 条，多连接下载可看多条。",
      "UDP 丢包和抖动比平均速度更重要，丢包高时游戏、QUIC、Hysteria2 和语音体验会明显变差。",
      "测试结果受测试时段、CPU、网卡、限速、运营商 QoS 和服务商流量策略影响。",
    ],
    keywords: "真实带宽 iperf TCP UDP 并发流数 丢包 抖动 重传 RTT",
  },
  {
    id: "client-speed-test",
    category: "tools",
    title: "本机单线程测速",
    path: "/client-speed-test",
    icon: Activity,
    adminOnly: true,
    summary:
      "在当前浏览器里使用 Cloudflare 测速端点测本机网络的单线程下载、上传、延迟、抖动、丢包和出口信息。",
    purpose:
      "本机单线程测速解决的是“我这台电脑或手机当前网络到公网测速点能跑多少”。它不经过 Agent，也不代表某个节点服务器的出口速度，而是帮助你判断本地宽带、5G、浏览器、DNS 或当前网络环境是否正常。",
    prerequisites: [
      "当前浏览器能访问 Cloudflare Speedtest 端点。",
      "浏览器没有被插件、系统代理或公司网络限制大文件请求。",
      "需要测超过 1G 级别单线程时，把最大下载量调大，避免测试太早结束。",
    ],
    steps: [
      "打开页面，按需要选择标准或深度测试。",
      "设置最大下载量、最大上传量、单线程/多阶段参数和是否启用上传测试。",
      "点击开始后不要切换网络，等待延迟、下载、上传和质量阶段完成。",
      "查看平均速度、峰值速度、已传输数据、空载延迟、负载延迟、抖动、丢包、IP、ASN、Cloudflare 机房和历史记录。",
      "需要留档时复制结果或下载报告。",
    ],
    result:
      "得到当前本机网络视角的测速结果，可用于判断问题是在本地网络、运营商、代理协议还是服务器线路。",
    notes: [
      "这是本机到 Cloudflare 的测试，不是 CloudNest 节点之间的真实带宽测试。",
      "浏览器测速会受 CORS、浏览器连接调度、CPU 和系统代理影响，极限值可与系统测速工具交叉验证。",
      "单线程速度低但多线程高，通常说明单连接拥塞、TCP 窗口、路由或中间链路对长连接不友好。",
    ],
    keywords: "本机测速 Cloudflare 单线程 下载 上传 延迟 抖动 丢包 IP ASN",
  },
  {
    id: "udp-quic-diagnostic",
    category: "tools",
    title: "UDP / QUIC 诊断",
    path: "/udp-quic-diagnostic",
    icon: RadioTower,
    adminOnly: true,
    summary: "专门检测 UDP 端口、UDP Echo 和 QUIC 握手是否可用。",
    purpose:
      "很多协议表面 TCP 正常，但 UDP 或 QUIC 被安全组、防火墙、运营商或 NAT 丢弃。UDP / QUIC 诊断把这类问题单独拿出来测，适合排查 Hysteria2、TUIC、WireGuard、QUIC 应用和游戏端口。",
    prerequisites: [
      "执行节点在线，目标节点或目标地址可访问。",
      "UDP Echo 模式通常需要目标节点配合；QUIC 模式需要目标端口真实支持 QUIC/TLS。",
      "云厂商安全组、系统防火墙和路由器端口转发都必须放行对应 UDP 端口。",
    ],
    steps: [
      "选择 UDP Echo 或 QUIC 模式。",
      "选择执行节点和目标节点，或填写外部目标地址与端口。",
      "设置包大小、次数、超时、空闲时间或 QUIC SNI。",
      "运行诊断后查看成功率、RTT、抖动、丢包、错误阶段和原始结果。",
      "如果 TCP 可用但 UDP 失败，优先检查安全组、防火墙、NAT 和运营商限制。",
    ],
    result: "能明确区分“服务没监听”“UDP 被挡”“QUIC 握手失败”和“网络抖动丢包”。",
    notes: [
      "UDP 没有 TCP 那样的连接建立过程，测试成功率和丢包率比单次结果更有意义。",
      "QUIC 失败不一定是端口不通，也可能是 SNI、证书、ALPN 或服务端协议不匹配。",
      "普通 Ping 正常不能证明 UDP 或 QUIC 正常。",
    ],
    keywords: "UDP QUIC Hysteria2 TUIC WireGuard Echo 丢包 SNI NAT",
  },
  {
    id: "ip-quality",
    category: "tools",
    title: "IP 质量检测",
    path: "/ip-quality",
    icon: MapPinned,
    adminOnly: true,
    summary:
      "检测节点出口 IP 的地区、ASN、风险评分、黑名单、流媒体、ChatGPT、DNS 观察和端口封禁。",
    purpose:
      "IP 质量检测用于判断一台出口机器是否适合做落地、代理或服务发布。它把公开地理信息、可选风险服务、DNSBL、流媒体可访问性、ChatGPT 可用性和 DNS 观察结果放在同一页，避免只看地区或只看 Ping 就误判出口质量。",
    prerequisites: [
      "执行节点 Agent 版本达到页面显示的最低要求。",
      "需要风险评分时，在情报源中配置 IPQualityScore 或 AbuseIPDB API Key。",
      "节点能访问检测目标站点；部分服务会因反自动化返回无法确认。",
    ],
    steps: [
      "打开页面，先在情报源中按需保存风险服务 API Key。",
      "选择节点点击开始检测，等待地区、风险、黑名单、解锁和 DNS 检查完成。",
      "打开详情查看各项原始结果、可信度、失败原因和最近历史。",
      "对高风险、黑名单命中或目标服务不可用的出口，避免用于重要用户或敏感业务。",
    ],
    result:
      "形成节点出口质量档案，帮助决定哪台机器做入口、哪台做中转、哪台做落地出口。",
    notes: [
      "没有配置风险服务时不会凭机房标签乱给风险分，只显示暂无风险分。",
      "服务检测只能证明当前测试时刻可访问，不能保证未来长期解锁。",
      "DNS 泄漏判断要结合系统配置和权威侧观察，不能只看单个解析器结果。",
    ],
    keywords:
      "IP质量 解锁 ChatGPT Netflix 风险评分 AbuseIPDB IPQS DNSBL DNS泄漏",
  },
  {
    id: "topology",
    category: "system",
    title: "全链路拓扑",
    path: "/topology",
    icon: GitBranch,
    summary: "用一张图查看域名、入口、转发、隧道、节点和内网服务之间的关系。",
    purpose:
      "拓扑图适合复杂多级隧道和多入口业务。异常组件会变色，帮助判断故障是在入口、转发、隧道、节点还是内网接入端。",
    prerequisites: ["已有节点、隧道、转发或发布服务时信息最完整。"],
    steps: [
      "打开页面查看完整关系图。",
      "根据颜色定位异常组件。",
      "点击组件进入对应管理页面处理。",
      "修复后返回拓扑确认链路恢复。",
    ],
    result: "减少在多个页面之间逐项猜测依赖关系的时间。",
    notes: ["拓扑展示现有资源关系，不会自动修改配置。"],
  },
  {
    id: "system-self-check",
    category: "system",
    title: "全系统自检",
    path: "/system-self-check",
    icon: SearchCheck,
    adminOnly: true,
    summary:
      "自动检查 Agent 身份、DNS 解析、端口账本、真实监听、域名、证书、转发和隧道断链。",
    purpose:
      "全系统自检存在的意义，是把“到底坏在哪一段”从人工猜测变成分段检查。它会把面板配置、数据库账本、Agent 上报、服务器真实监听、DNS 公网解析和证书状态放到一起核对，直接提示问题段和建议修复方向。",
    prerequisites: [
      "管理员登录。",
      "需要检查的节点或家庭设备尽量在线；离线资源会被标记为无法确认或失败。",
      "DNS、证书和端口类检查需要面板服务器能够访问外部解析和目标端口。",
    ],
    steps: [
      "打开页面选择全量自检，或只检查节点、DNS、端口、域名证书、转发和隧道。",
      "查看每个检查项的状态：正常、警告、失败或跳过。",
      "展开失败项，按提示区分是面板配置、Agent、服务器监听、DNS、证书还是外部网络问题。",
      "修复后重新运行同一类检查，确认问题消失。",
    ],
    result:
      "不用逐台登录服务器，也能快速定位常见断链、密钥装错、端口账本不一致和 DNS 解析异常。",
    notes: [
      "无 IPv6、无 OpenWrt 或没有家庭设备的环境会跳过对应检查，不代表失败。",
      "面板之外的路由器品牌和手机网络限制只能通过结果推断，不能被面板完全控制。",
      "自检不会自动删除资源；涉及停用、重装或删除时仍由管理员确认。",
    ],
    keywords: "自检 Agent密钥 DNS AAAA OpenWrt 端口账本 证书 断链 故障定位",
  },
  {
    id: "monitoring",
    category: "system",
    title: "告警中心",
    path: "/monitoring",
    icon: BellRing,
    summary: "集中查看节点、隧道、转发、证书和动态 DNS 的异常与恢复记录。",
    purpose:
      "告警中心把短暂波动和持续故障记录下来，并可按资源、严重程度和时间筛选。管理员还可以配置 Telegram 通知及非白名单 IP 登录提醒。",
    prerequisites: [
      "查看告警无需额外配置；Telegram 通知需要 Bot Token 和 Chat ID。",
    ],
    steps: [
      "先在告警列表查看未处理事件和触发时间。",
      "切换资源视图检查各资源当前状态及可用率。",
      "管理员在通知设置中填写 Telegram 信息并发送测试消息。",
      "分别启用需要的节点、隧道、转发、恢复、证书、动态 DNS或登录告警，并设置重复次数。",
    ],
    result: "故障发生和恢复都有简明记录，选择的事件可主动发送 Telegram。",
    notes: [
      "避免为所有类别设置高频重复通知，否则线路波动时消息会过多。",
      "告警恢复表示监控状态恢复，不代表旧连接一定自动续上。",
    ],
  },
  {
    id: "ai-ops",
    category: "system",
    title: "AI 运维",
    path: "/ai-ops",
    icon: Bot,
    adminOnly: true,
    summary: "用自己配置的大模型对话式检查和维护服务器，或按计划自动巡检。",
    purpose:
      "AI 运维助手内置面板全部功能说明，可直接回答功能在哪、怎么操作；选中节点后可读取运行数据、做网络诊断、检查磁盘与服务，并在授权范围内执行维护。",
    prerequisites: [
      "在“模型配置”填写兼容 OpenAI 的模型地址、名称和密钥；需支持流式回复和工具调用。",
      "需要检查服务器时，在“本轮节点与权限”中勾选节点。",
    ],
    steps: [
      "在“实时对话”直接提问，例如“私人代理的出口怎么换成 Mysterium”或“这条转发为什么慢”。",
      "选中相关节点后，助手可读取告警、可用率、流量、出口状态和 CPU/内存历史，并从节点做 ping、TCP 延迟、路由追踪、HTTP 计时、DNS 和节点间测速。",
      "只读模式只检查不修改；切换“允许维护”并开启“完整服务器管理”后，助手可在所选节点执行命令、安装或修复服务。",
      "检查很多台服务器时（如“检查全部节点的磁盘”），助手用批量检查返回逐台状态表，异常标红。",
      "在“健康报告”开启告警自动诊断和每周健康报告：告警持续约 45 秒后自动只读排查，原因和建议写进告警中心并推送 Telegram；每周一 9 点生成健康周报，也可随时“立即生成报告”。",
      "在“定时任务”用自然语言写巡检要求并设置时间，结果可推送 Telegram。",
      "问安全问题时助手会做只读安全巡检：SSH 设置、开放端口、暴力登录来源、fail2ban、防火墙、待装安全更新等，并按风险给出加固建议。",
      "“记忆与改动”页：节点笔记记录助手查到的系统、服务和处理过的问题，下次对话自动带上，也可手动修改；改动记录列出助手执行过的命令，用“写入文件”改过的配置文件可看差异并一键还原；还可设置可切换的模型和 token 单价。",
      "对话中可以重新生成最后一条回复、编辑之前的问题后重发、搜索和重命名会话、导出 Markdown；每轮回复显示 token 用量和估算费用，思考型模型的思考过程可以展开查看。",
    ],
    result: "不用逐页查找，助手按面板知识和实际检查结果给出原因和处理步骤。",
    notes: [
      "检查结果会发送给你配置的模型服务。",
      "节点间测速每次最多产生约 400 MB 流量，并占用目标节点 5201 端口。",
      "资源历史每 5 分钟记录一次、保留 7 天，面板升级后开始积累。",
    ],
  },
  {
    id: "assets",
    category: "system",
    title: "服务器资产",
    path: "/server-assets",
    icon: CloudCog,
    adminOnly: true,
    summary: "记录服务器厂商、地区、配置、价格、到期时间和续费信息。",
    purpose:
      "资产中心管理的是服务器采购和续费资料，不会改变节点上的网络配置。它适合统计月度成本，并在到期前产生提醒。",
    prerequisites: ["节点可以先存在；资产资料也可按实际采购信息补录。"],
    steps: [
      "登记服务器资产并关联节点。",
      "填写厂商、地区、配置、价格、购买和到期时间。",
      "补充 IP、ASN、套餐、标签和备注。",
      "在告警中心启用资产到期 Telegram 通知。",
    ],
    result: "服务器成本、归属和续费日期集中可查。",
    notes: ["删除资产资料不会删除节点；删除节点前仍需遵守业务依赖规则。"],
  },
  {
    id: "users",
    category: "system",
    title: "用户管理",
    path: "/user",
    icon: Users,
    adminOnly: true,
    summary: "创建普通用户，并按隧道、节点、端口范围和流量额度分配权限。",
    purpose:
      "用户管理把管理员资源安全地分享给不同用户，同时保留资源所有权、端口占用和流量归属。普通用户也可以创建自己的节点和隧道，管理员能够看到所有者标记。",
    prerequisites: ["先准备需要分享的节点、隧道或端口资源。"],
    steps: [
      "新增或编辑用户，设置账号、状态和到期时间；可选择永久。",
      "在第一页逐项添加共享隧道、共享节点和端口资源。",
      "在私人代理授权中选择任意一种协议，并设置独立流量额度、重置日和到期时间；支持的协议可配置仅对该用户生效的限速。",
      "保存后用普通用户账户核对仪表板及可用资源。",
    ],
    result: "用户只能使用被授权资源；每项资源达到额度后单独停用并显示原因。",
    notes: [
      "总流量是各项资源额度之和，但每项额度独立执行。",
      "共享节点不可被普通用户修改；共享隧道也不可修改。",
      "用户使用共享节点组隧道或做转发时，仍受全局端口账本和分享范围约束。",
      "独立限速规则已经并入用户资源授权和转发策略；旧的 /limit 入口会跳回用户管理。",
    ],
  },
  {
    id: "profile",
    category: "system",
    title: "账户设置",
    path: "/profile",
    icon: KeyRound,
    summary: "查看个人资料、打开使用教程，并进入当前账号可用的功能入口。",
    purpose:
      "账户设置是用户自己的入口页。普通用户可以从这里查看资料、进入被授权的资源；管理员也可以快速跳转到常用管理功能。它不承担资源配置本身，主要用于账号级操作和导航。",
    prerequisites: ["登录任意有效账号。"],
    steps: [
      "打开账户设置查看当前账号、角色和可用入口。",
      "需要学习功能时点击使用教程。",
      "需要修改登录密码时使用左侧菜单底部或顶部账户区域的密码修改入口。",
      "普通用户按页面展示的授权资源进入对应功能。",
    ],
    result: "用户能清楚知道自己能用哪些功能，并能快速进入教程或个人操作入口。",
    notes: [
      "普通用户不会看到管理员的限制性参数和后台策略，只显示续费、额度、到期等必要信息。",
      "账号密码修改后通常需要重新登录。",
    ],
    keywords: "账户设置 个人资料 普通用户 教程 密码",
  },
  {
    id: "settings",
    category: "system",
    title: "网站设置",
    path: "/config",
    icon: Settings,
    adminOnly: true,
    summary: "管理面板访问域名、Agent 通信地址、应用名称和登录验证。",
    purpose:
      "网站设置把浏览器访问入口与 Agent 通信地址分开管理，并复用域名管理中的 DNS 和托管 HTTPS 能力。",
    prerequisites: [
      "仅管理员可修改。",
      "新增 HTTPS 面板域名前，先在域名管理添加 Cloudflare 配置，并准备一台 443 端口可用的在线入口节点。",
    ],
    steps: [
      "在“面板访问入口”查看当前地址和证书状态；需要新域名时点击“新增面板域名”。",
      "选择已登记的主域名、填写面板子域名并选择 HTTPS 入口节点，提交后等待 DNS 和证书完成。",
      "Agent 通信地址继续填写公网 IP:端口，不要填写 HTTPS 域名或 CDN 地址。",
      "按需修改应用名称与登录验证码并保存。",
    ],
    result: "浏览器可通过托管 HTTPS 域名访问面板，Agent 继续使用独立通信地址。",
    notes: [
      "新增面板域名不会替换或删除现有访问地址。",
      "保留原始 IP 地址作为域名或证书异常时的救援入口。",
      "异机 HTTPS 入口必须能够访问面板的 Agent 通信地址。",
    ],
  },
  {
    id: "update",
    category: "system",
    title: "检查更新",
    path: "/update",
    icon: RefreshCw,
    adminOnly: true,
    summary: "检查 GitHub 版本并执行带状态反馈的面板更新。",
    purpose:
      "检查更新用于升级面板前后端和数据库迁移。页面会展示排队、构建、重启、健康检查和失败原因。节点 Agent 版本则在节点页面单独升级。",
    prerequisites: [
      "面板通过受支持的一键脚本安装或已安装宿主机更新服务。",
      "更新前确认磁盘空间、Docker 状态和 GitHub 网络可用。",
    ],
    steps: [
      "点击检查版本，阅读版本说明和风险提示。",
      "重要升级前备份数据库及配置。",
      "执行立即更新并等待健康检查完成，不要在构建阶段反复点击。",
      "更新后核对版本、节点在线状态和核心转发。",
    ],
    result: "面板升级到目标发布版本；失败时页面保留阶段和错误信息用于处理。",
    notes: [
      "面板更新与 Agent 更新是两套流程。",
      "更新超时不一定代表构建仍在进行，应根据页面状态、容器和更新日志判断。",
    ],
  },
];

const quickSteps = [
  { number: "01", title: "添加节点", detail: "让服务器上线", path: "/node" },
  { number: "02", title: "组建隧道", detail: "确定流量路径", path: "/tunnel" },
  { number: "03", title: "创建转发", detail: "开放业务端口", path: "/forward" },
  {
    number: "04",
    title: "检查状态",
    detail: "确认链路可用",
    path: "/monitoring",
  },
];

export default function GuidePage() {
  const navigate = useNavigate();
  const admin = isAdmin();
  const [category, setCategory] = useState<"all" | GuideCategory>("all");
  const [query, setQuery] = useState("");

  const filteredEntries = useMemo(() => {
    const normalized = query.trim().toLowerCase();

    return entries.filter((entry) => {
      if (category !== "all" && entry.category !== category) return false;
      if (!normalized) return true;

      return [
        entry.title,
        entry.summary,
        entry.purpose,
        entry.keywords,
        ...entry.steps,
        ...entry.notes,
      ]
        .filter(Boolean)
        .join(" ")
        .toLowerCase()
        .includes(normalized);
    });
  }, [category, query]);

  const scrollToEntry = (id: string) => {
    document
      .getElementById(`guide-${id}`)
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  return (
    <div className="mx-auto w-full max-w-[1560px] space-y-6 p-4 md:p-6">
      <header className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <div className="flex items-center gap-1.5">
            <h1 className="text-2xl font-bold text-foreground">使用教程</h1>
            <PageHelp>
              从节点接入到入口容灾，按真实依赖关系了解每个模块为什么存在、如何配置，以及配置完成后会得到什么。
            </PageHelp>
          </div>
        </div>
        <Input
          isClearable
          aria-label="搜索教程"
          className="w-full lg:w-80"
          placeholder="搜索功能或问题"
          startContent={<Search className="text-default-400" size={17} />}
          value={query}
          onClear={() => setQuery("")}
          onValueChange={setQuery}
        />
      </header>

      <section aria-labelledby="quick-start-title">
        <div className="flex items-end justify-between gap-4">
          <div>
            <p className="text-xs font-medium text-primary">第一次使用</p>
            <h2 className="mt-1 text-lg font-semibold" id="quick-start-title">
              最短开通流程
            </h2>
          </div>
          <span className="hidden text-xs text-default-500 sm:block">
            节点是资源，隧道是路径，转发才是对外服务
          </span>
        </div>
        <div className="mt-4 grid overflow-hidden rounded-lg border border-divider sm:grid-cols-2 xl:grid-cols-4">
          {quickSteps.map((step, index) => (
            <button
              key={step.number}
              className={`group flex min-h-24 items-center gap-3 px-4 py-4 text-left transition-colors hover:bg-default-100 ${index > 0 ? "border-t border-divider sm:border-t-0 sm:border-l xl:border-l" : ""} ${index === 2 ? "sm:border-l-0 xl:border-l" : ""}`}
              type="button"
              onClick={() => navigate(step.path)}
            >
              <span className="font-mono text-xs text-default-400">
                {step.number}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-semibold">
                  {step.title}
                </span>
                <span className="mt-1 block text-xs text-default-500">
                  {step.detail}
                </span>
              </span>
              <ArrowRight
                className="text-default-300 transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
                size={16}
              />
            </button>
          ))}
        </div>
      </section>

      <section aria-label="核心关系" className="border-y border-divider py-4">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Chip startContent={<Server size={14} />} variant="flat">
            节点 · 承载服务器
          </Chip>
          <ArrowRight className="text-default-400" size={15} />
          <Chip startContent={<GitBranch size={14} />} variant="flat">
            隧道 · 网络路径
          </Chip>
          <ArrowRight className="text-default-400" size={15} />
          <Chip startContent={<Network size={14} />} variant="flat">
            转发 · 公网服务
          </Chip>
          <ArrowRight className="text-default-400" size={15} />
          <Chip startContent={<Globe2 size={14} />} variant="flat">
            域名与容灾 · 访问入口
          </Chip>
        </div>
      </section>

      <Tabs
        aria-label="教程分类"
        classNames={{
          base: "max-w-full overflow-x-auto",
          tabList: "px-0",
          tab: "h-10 whitespace-nowrap",
        }}
        selectedKey={category}
        variant="underlined"
        onSelectionChange={(key) =>
          setCategory(String(key) as "all" | GuideCategory)
        }
      >
        {categories.map((item) => (
          <Tab key={item.key} title={item.label} />
        ))}
      </Tabs>

      <div className="grid gap-8 lg:grid-cols-[220px_minmax(0,1fr)]">
        <aside className="hidden lg:block">
          <div className="sticky top-4 max-h-[calc(100vh-7rem)] overflow-y-auto border-l border-divider pl-4">
            <p className="mb-3 text-xs font-semibold text-default-500">
              本页目录
            </p>
            <nav aria-label="教程目录" className="space-y-1">
              {filteredEntries.map((entry) => (
                <button
                  key={entry.id}
                  className="block w-full truncate py-1.5 text-left text-sm text-default-500 transition-colors hover:text-primary"
                  type="button"
                  onClick={() => scrollToEntry(entry.id)}
                >
                  {entry.title}
                </button>
              ))}
            </nav>
          </div>
        </aside>

        <main className="min-w-0">
          {filteredEntries.length === 0 ? (
            <div className="flex min-h-64 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider text-default-500">
              <BookOpen size={30} />
              <p>没有找到相关教程</p>
              <Button
                size="sm"
                variant="flat"
                onPress={() => {
                  setQuery("");
                  setCategory("all");
                }}
              >
                查看全部
              </Button>
            </div>
          ) : (
            <Accordion
              className="px-0"
              selectionMode="multiple"
              variant="light"
            >
              {filteredEntries.map((entry) => {
                const Icon = entry.icon;
                const blocked = Boolean(entry.adminOnly && !admin);

                return (
                  <AccordionItem
                    key={entry.id}
                    aria-label={`${entry.title}使用教程`}
                    classNames={{
                      base: "scroll-mt-4 border-b border-divider",
                      trigger: "gap-3 px-0 py-4",
                      content: "pb-6 pl-0 sm:pl-12",
                    }}
                    id={`guide-${entry.id}`}
                    startContent={
                      <span className="flex h-9 w-9 flex-none items-center justify-center rounded-md bg-default-100 text-default-600">
                        <Icon size={18} />
                      </span>
                    }
                    title={
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-semibold">{entry.title}</span>
                          {entry.adminOnly && (
                            <Chip size="sm" variant="flat">
                              仅管理员
                            </Chip>
                          )}
                        </div>
                        <p className="mt-1 line-clamp-2 text-sm font-normal text-default-500">
                          {entry.summary}
                        </p>
                      </div>
                    }
                  >
                    <div className="space-y-5 text-sm leading-6">
                      <section>
                        <h3 className="font-semibold">为什么使用</h3>
                        <p className="mt-1 text-default-600">{entry.purpose}</p>
                      </section>
                      <section>
                        <h3 className="font-semibold">使用前准备</h3>
                        <ul className="mt-2 space-y-1.5 text-default-600">
                          {entry.prerequisites.map((item) => (
                            <li key={item} className="flex gap-2">
                              <span className="mt-2 h-1.5 w-1.5 flex-none rounded-full bg-default-400" />
                              <span>{item}</span>
                            </li>
                          ))}
                        </ul>
                      </section>
                      <section>
                        <h3 className="font-semibold">操作步骤</h3>
                        <ol className="mt-2 space-y-2">
                          {entry.steps.map((step, index) => (
                            <li
                              key={step}
                              className="grid grid-cols-[24px_minmax(0,1fr)] gap-2 text-default-600"
                            >
                              <span className="flex h-6 w-6 items-center justify-center rounded-full bg-primary-50 text-xs font-semibold text-primary dark:bg-primary-500/10">
                                {index + 1}
                              </span>
                              <span>{step}</span>
                            </li>
                          ))}
                        </ol>
                      </section>
                      <section className="border-l-2 border-success pl-3">
                        <h3 className="font-semibold">完成后</h3>
                        <p className="mt-1 text-default-600">{entry.result}</p>
                      </section>
                      <section>
                        <h3 className="font-semibold">注意事项</h3>
                        <ul className="mt-2 space-y-1.5 text-default-600">
                          {entry.notes.map((item) => (
                            <li key={item} className="flex gap-2">
                              <span className="mt-2 h-1.5 w-1.5 flex-none rounded-full bg-warning" />
                              <span>{item}</span>
                            </li>
                          ))}
                        </ul>
                      </section>
                      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-divider pt-4">
                        <span className="text-xs text-default-500">
                          {categoryLabels[entry.category]}
                          {blocked ? " · 当前账户不可操作" : ""}
                        </span>
                        <Button
                          color="primary"
                          endContent={<ArrowRight size={15} />}
                          isDisabled={blocked}
                          size="sm"
                          variant="flat"
                          onPress={() => navigate(entry.path)}
                        >
                          打开{entry.title}
                        </Button>
                      </div>
                    </div>
                  </AccordionItem>
                );
              })}
            </Accordion>
          )}
        </main>
      </div>

      <footer className="flex flex-col gap-3 border-t border-divider py-5 text-sm text-default-500 sm:flex-row sm:items-center sm:justify-between">
        <span>遇到异常时，先看告警中心，再用全链路拓扑和网络诊断定位。</span>
        <div className="flex gap-2">
          <Button
            size="sm"
            startContent={<Activity size={15} />}
            variant="flat"
            onPress={() => navigate("/monitoring")}
          >
            告警中心
          </Button>
          <Button
            size="sm"
            startContent={<KeyRound size={15} />}
            variant="flat"
            onPress={() => navigate("/profile")}
          >
            账户设置
          </Button>
        </div>
      </footer>
    </div>
  );
}
