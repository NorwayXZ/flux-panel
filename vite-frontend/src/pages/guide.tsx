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
  Boxes,
  CloudCog,
  GitBranch,
  Globe2,
  Home,
  KeyRound,
  LayoutDashboard,
  LockKeyhole,
  Network,
  RadioTower,
  RefreshCw,
  Search,
  Server,
  Settings,
  ShieldCheck,
  SquareTerminal,
  Users,
  Waypoints,
  Wrench,
  type LucideIcon,
} from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";

import { isAdmin } from "@/utils/auth";

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
    summary: "按电信、联通、移动的 DNS 线路，把新连接分配到不同公网入口。",
    purpose:
      "当不同 VPS 对三家运营商的网络质量不同，三网优化可以让电信用户进入电信友好入口、联通用户进入联通友好入口、移动用户进入移动友好入口。运营商识别由权威 DNS 服务商完成，面板不读取客户 IP。",
    prerequisites: [
      "主域名实际使用 DNSPod 或阿里云 DNS 作为权威 DNS。",
      "资源中心已保存对应 DNS 凭据。",
      "已经创建至少两条使用不同入口节点的正常转发。",
    ],
    steps: [
      "先在资源中心的“域名管理”保存 DNSPod 或阿里云 DNS 凭据。",
      "点击“新建三网优化”，填写主域名和业务域名。",
      "选择默认入口，再分别选择电信、联通和移动入口；留空的线路使用默认入口。",
      "保存并同步线路 DNS，然后在当前页面查看每条运营商入口和活动记录。",
    ],
    result: "不同运营商发起的新连接可解析到各自更合适的公网入口。",
    notes: [
      "这不是根据持续流量把单个用户来回迁移，而是在 DNS 查询阶段分配入口。",
      "已有连接不会迁移；DNS 缓存可能使变化延迟生效。",
      "Cloudflare DNS 不提供这里需要的中国大陆三网线路解析，因此当前使用 DNSPod 或阿里云 DNS。",
    ],
    keywords: "移动 联通 电信 运营商 DNS 线路解析",
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
    ],
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
      <header className="flex flex-col gap-4 border-b border-divider pb-5 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="text-sm text-default-500">帮助中心</p>
          <h1 className="mt-1 text-2xl font-semibold">使用教程</h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-default-500">
            从节点接入到入口容灾，按真实依赖关系了解每个模块为什么存在、如何配置，以及配置完成后会得到什么。
          </p>
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
            <div className="flex min-h-64 flex-col items-center justify-center gap-3 border-y border-divider text-default-500">
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
