# Flux Panel Enhanced

基于 [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel) 持续维护的增强版本，用于集中管理 GOST 节点、隧道、端口转发、用户权限和流量限制。

> 本项目涉及网络转发能力。请仅在拥有管理权限的服务器上部署，并遵守所在地法律法规及服务商使用条款。

## 主要增强

- 支持 `A-B-C`、`A-B-C-D-E` 等多级隧道
- 隧道与转发诊断展示分段延迟、丢包率和总延迟
- 节点离线时，关联隧道和转发同步标记为异常
- 节点与隧道按照正常、异常分区，并在分区内按创建时间倒序排列
- 多级隧道按照链路级数独立分组，避免不同高度卡片相互影响
- 转发管理按照隧道归纳，集中展示隧道下的全部转发
- 转发状态同时考虑服务状态与完整链路状态
- 离线节点支持级联清理关联隧道、转发、授权和限速规则
- 删除隧道时自动删除该隧道下的转发及关联数据
- 在线节点仍受删除保护，降低误删正常业务的风险
- 支持单线路、主备线路和低延迟选路
- 支持候选线路与目标地址主动健康检查
- 支持 TCP、UDP、TCP + UDP，以及端口段批量映射
- 深色模式使用低饱和异常配色，保持警示清晰但不过度刺眼
- 侧边栏包含管理员在线更新入口，可检查版本、确认更新并查看执行日志
- 统计、共享权限、节点、隧道、转发、限速和用户卡片支持拖动排序
- 转发线路选择按端口转发、2 级隧道、3 级隧道等类型分组并标注
- 告警中心统一记录节点、隧道和转发异常，恢复后自动结案
- 支持 24 小时、7 天、30 天在线率趋势、故障次数和资源状态历史
- 线路自动容灾支持连续故障确认、恢复稳定期、切换冷却和完整切换记录
- 用户可在一次创建或编辑中配置自有资源、共享隧道和共享节点的独立额度
- 用户流量与转发额度按资源自动汇总，资源用尽只停用依赖该资源的业务
- 支持通过主动连接的内网接入端，将公网节点端口发布到家庭网络、局域网和 NAT 后方的 TCP 服务
- 支持端口池、自动分配、指定端口、租期、续租、到期停用、冷却和自动释放
- 支持通过 Agent 加密通道打开管理员网页终端，无需额外开放 SSH 端口

### 安全远程终端

`2.8.0` 为在线节点增加管理员网页终端。终端复用 Agent 主动建立的加密 WebSocket 通道，不要求节点暴露 SSH 端口，也不会读取或保存服务器 SSH 密码。

- 终端默认逐节点关闭，仅管理员可以启用和连接；共享节点的普通用户没有入口和接口权限。
- 启用、连接和关闭终端都需要重新验证当前管理员密码。连续失败 5 次后暂时锁定 15 分钟。
- 浏览器使用 60 秒有效且只能消费一次的随机票据建立独立 WebSocket 会话，票据在请求日志中自动脱敏。
- 每个节点同时只允许一个终端会话，整个面板最多三个；空闲 10 分钟或运行 60 分钟后自动结束。
- Agent 将 Shell 放在独立 PTY 中，限制消息大小和终端尺寸；浏览器断开、节点离线或 Agent 停止时强制清理进程。
- 审计只记录操作者、节点、来源 IP、开始时间、结束时间和结束原因，不记录命令、终端输出或密码。
- Agent 必须升级到 `2.8.0` 或更高版本。旧 Agent 的节点监控、隧道和转发继续工作，但不能启用网页终端。

Agent 通常以 root 运行，因此网页终端也可能获得 root 权限。这项能力扩大了面板管理员账号失窃后的影响范围，应使用强密码、HTTPS 和受限的面板访问来源，并在不需要时关闭节点终端。升级后端只会向 `node` 表新增 `terminal_enabled` 字段并创建 `terminal_session_audit` 表，不会改写节点、隧道、转发或用户数据。手动维护数据库时可执行 [`migrations/20260726_secure_terminal.sql`](migrations/20260726_secure_terminal.sql)。

### 共享节点名额与用户配置修复

`2.7.1` 修复用户资源配置和共享节点名额展示。流量重置日期现在会在账号、隧道和节点额度编辑器中稳定回显；用户卡片只保留一个“编辑”入口，账号和全部资源权限统一在同一流程保存。

- 组建隧道本身不占用转发名额；只有创建实际转发时才占用。
- 直接使用一台共享节点做端口转发，占用该节点 `1` 个转发名额。
- 使用三台共享节点组成的用户自建隧道创建一条转发，会在三台节点上各占 `1` 个名额。
- 同一条转发的多条候选线路重复经过同一节点时，该节点只计算 `1` 个名额，避免主备线路重复扣除。
- 任一经过节点名额耗尽后，依赖该节点的新转发会被拒绝，并显示具体节点名称和原因；其他不经过该节点的业务不受影响。
- 普通用户仪表板显示每台共享节点的已用、上限、剩余名额和流量重置日。

该版本不新增数据库表，不改写现有节点、隧道、转发或额度记录，也不增加 Agent 的常驻资源要求。

### 内网服务发布与端口租约

`2.7.0` 新增独立的“服务发布”业务，不改变原有节点、隧道和转发的数据结构。管理员先在“端口资源”创建公网端口池，用户再在“服务发布”添加内网接入端并发布服务。

- 内网接入端主动连接面板和公网节点，家庭宽带或局域网无需开放入站管理端口。
- 公网节点使用带随机强认证的 GOST SOCKS5 BIND 入口，接入端使用原生 `rtcp` 将租用端口反向映射到允许网段内的目标。
- 面板只负责配置和状态控制，业务流量不经过 Spring Boot 或 MySQL。
- 第一版支持 TCP；UDP 反向会话和同一服务多公网入口容灾尚未开放。
- 默认允许目标为 `127.0.0.1/32`、`10.0.0.0/8`、`172.16.0.0/12` 和 `192.168.0.0/16`，并拒绝云主机元数据地址。创建接入端时可以收紧允许网段。
- 端口唯一性按实际公网网络命名空间检查。相同公网地址的重复节点记录、原有转发和端口池不能占用同一端口。
- 删除或到期时先清理接入端配置，再进入冷却期并释放端口。接入端离线时保持端口占用并自动重试，防止旧配置恢复后与新租户撞端口。
- 管理员能看到全部用户的发布服务及归属账号；普通用户只能管理自己的接入端和发布服务。
- 内网接入端默认安装到 `/etc/flux-connector`，服务名为 `flux-connector`；它可以与同机的普通节点 Agent 并存，不会覆盖 `/etc/gost` 或 `gost` 服务。

端口池的控制端口必须允许接入端访问，租用端口范围按业务需要对公网开放。`2.7.0` Agent 会将控制服务限制为 BIND-only 并拒绝普通 SOCKS5 CONNECT；创建端口池前，公网节点 Agent 必须先升级到 `2.7.0` 或更高版本。仍应使用防火墙限制控制端口的来源地址，形成第二层保护，并且不应公开展示认证信息。接入端复用现有 GOST Agent 二进制，空闲资源消耗与普通节点 Agent 接近；每个并发连接仍会消耗公网节点和接入端的文件描述符、内存与带宽，大并发场景需要提高系统限制和服务器配置。

升级后端会自动创建 `internal_connector`、`port_pool`、`published_service`、`port_lease`、`port_lease_event` 和分配锁表。手动维护数据库时可以执行 [`migrations/20260725_service_publishing.sql`](migrations/20260725_service_publishing.sql)。迁移仅新增表，不修改或删除现有业务数据；回退旧版本前停止新发布服务即可，现有节点、隧道和转发不受影响。

### 管理员运维仪表板

`2.6.2` 将管理员仪表板空白区域整理为运维总览，管理员登录后可以在一个页面快速判断当前系统是否需要处理：

- 顶部指标显示全部用户 24 小时计费流量、在线节点、健康隧道和运行中转发。
- 资源健康概览分别统计节点、隧道和转发的健康、降级、异常、暂停和未知数量，并提供直达管理页面的入口。
- 异常与待处理显示当前未结案告警、资源名称、所属账号、异常原因和持续时间。
- 转发流量排行按累计计费流量排序；它反映转发累计使用量，不冒充单日流量统计。
- 额度与到期风险列出禁用、已到期、即将到期或额度接近用尽的用户。
- 线路切换动态显示最近发生自动容灾切换的转发；没有切换记录时明确显示空状态。
- 所有新增区域在手机端自动纵向排列，不改变普通用户首页的套餐和权限展示。

管理员仪表板只读取现有监控、转发、用户和套餐数据，不新增数据库表；升级后刷新页面即可使用。

### 用户资源额度与独立停用

`2.6.0` 将原来的单一用户套餐升级为资源额度模型。管理员可在同一个窗口完成账号创建、共享隧道、共享节点、流量额度、转发名额、重置日期和有效期配置。

`2.6.1` 重新整理了用户配置流程：账号、共享隧道和共享节点集中在默认页面，通过下拉框可连续添加多项资源；每项资源在独立额度窗口中编辑，点击“保存并返回”只更新当前表单并回到资源总览，最后点击“保存全部配置”或“创建并分配资源”才提交到服务器。用户自有资源、账户期限、重置规则和状态统一移至“高级设置”。

- 自有资源、每条共享隧道、每台共享节点分别拥有独立流量和转发名额。
- 新建用户的自有资源额度默认为 `0`，只有管理员明确分配后才计入汇总；因此只选择示例中的三项资源时汇总恰好为 `1200 GB`。
- 汇总额度由面板自动计算。例如 A 隧道 `100 GB`、B 隧道 `100 GB`、C 节点 `1000 GB`，用户总额度显示为 `1200 GB`。
- A 隧道达到 `100 GB` 后，仅暂停依赖 A 的转发；B 隧道、C 节点及其业务继续运行。
- 普通用户的隧道页面会明确显示“流量额度已用尽”“转发名额已用尽”“权限已到期”或“管理员已禁用”，不再把额度问题误报为节点离线。
- 用户使用管理员直接共享的隧道时只扣该隧道额度，不重复扣路径中的共享节点额度。
- 用户使用共享节点组建自己的隧道时，流量计入该路径涉及的共享节点额度；完全使用自有节点时计入自有资源额度。
- 故障切换和低延迟选路按实际承载线路计费，不固定记到主线路。
- 单向计费只统计上传流量；双向计费统计上传与下载；流量倍率只应用一次。
- 流量、转发数和有效期均有明确的“无限制”开关，`0` 始终表示没有额度，不再兼作无限制。

升级时后端会自动补齐额度字段并允许账户和资源永久有效。已有节点共享权限默认保持流量、转发无限制，避免升级导致现有业务意外中断；管理员可在用户编辑页逐项改为有限额度。历史流量不会重新计算，新计费口径从升级后的新上报数据开始生效。

### 线路自动容灾增强

`2.5.0` 对主备切换和低延迟选路进行了防抖增强。当前承载线路达到连续失败阈值后，会立即切换到优先级最高的健康备用线路；紧急故障切换不受冷却时间和恢复稳定期限制。

- 默认连续失败 2 次才将线路判定为异常，过滤偶发探测超时。
- 异常线路需要连续成功 2 次才能恢复为健康状态。
- 主线路恢复后需要稳定 180 秒才允许自动回切，避免刚恢复就再次掉线。
- 两次非紧急切换之间默认冷却 120 秒，低延迟模式还要求至少改善 15 ms。
- GOST 主服务更新失败时保留原承载线路，并记录失败原因，不写入错误的活动线路。
- 转发卡片固定显示主线或备用线承载状态、可用线路数量；独立详情弹窗展示每条候选线路和最近 50 条切换记录。
- 后端防止上一轮健康检查未完成时重复启动新一轮任务。

可通过 `FORWARD_HEALTH_CHECK_INTERVAL_MS`、`FORWARD_FAILURE_THRESHOLD`、`FORWARD_RECOVERY_THRESHOLD`、`FORWARD_SWITCH_COOLDOWN_MS`、`FORWARD_FAILBACK_STABLE_MS` 和 `FORWARD_LATENCY_SWITCH_GAP_MS` 调整策略。升级时会自动添加转发容灾字段和 `forward_route_switch` 历史表；手动迁移文件为 [`migrations/20260725_forward_failover.sql`](migrations/20260725_forward_failover.sql)。

### 告警中心与历史监控

`2.4.0` 新增告警中心。后端每 30 秒评估一次节点、隧道和转发状态：节点以 Agent WebSocket 实时连接为准，隧道检查完整节点路径，转发同时检查活动线路、候选线路和目标地址健康结果。

- 节点、隧道和转发统一使用正常、性能下降、异常、已暂停、未知状态。
- 异常和性能下降会生成告警；恢复或人工暂停后，待处理告警自动结案。
- 告警支持按状态、资源类型、严重程度和关键词筛选，并可单条或全部标记已读。
- 管理员可查看所有用户资源并看到所属账号；普通用户只能看到自己拥有或被分享资源的告警。
- 在线率按状态变化区间计算，人工暂停和未知时段不计入 SLA 分母。
- 默认保留 90 天已结束历史和已恢复告警，每天自动清理一次。
- 只在状态发生变化时新增历史区间，持续状态只更新时间，不会按分钟堆积重复采样记录。

可在 `/etc/flux-panel/flux-panel.env` 中调整 `MONITORING_SCAN_INTERVAL_MS` 和 `MONITORING_RETENTION_DAYS`。扫描间隔不建议低于 10 秒；历史保留天数低于 30 时，后端仍按最低 30 天执行清理。

升级后端会自动创建 `monitoring_current`、`monitoring_history`、`monitoring_alert` 和 `monitoring_alert_read` 表。手动维护数据库时可以执行 [`migrations/20260725_monitoring_alerts.sql`](migrations/20260725_monitoring_alerts.sql)，该迁移可重复执行。

### 卡片拖动排序

卡片标题区域右侧带有拖动手柄。按住手柄即可像整理手机应用一样调整卡片位置，点击卡片中的编辑、诊断、删除等按钮不会触发拖动。

- 每个账号独立保存自己的卡片顺序，不会影响其他管理员或普通用户。
- 顺序保存到面板数据库，换浏览器或设备登录同一账号后仍会同步。
- 浏览器会保留一份本地副本，后端暂时不可用时仍可继续使用上次顺序。
- 节点的在线/离线分区、隧道的正常/异常及链路级数分区保持固定，卡片只在所属分区内排序。
- 新建卡片默认排在当前卡片列表前面，随后可以手动移动。

`2.3.0` 启动时会自动创建 `layout_preference` 表。手动维护数据库时也可以执行 [`migrations/20260725_layout_preferences.sql`](migrations/20260725_layout_preferences.sql)，该迁移可重复执行。

## 架构

| 组件 | 技术 | 默认端口 | 作用 |
| --- | --- | ---: | --- |
| Web 前端 | React、TypeScript、Vite、HeroUI | `6366` | 管理界面 |
| 后端 | Spring Boot、MyBatis-Plus | `6365` | API、节点连接与任务调度 |
| 数据库 | MySQL 5.7（amd64）/ MySQL 8.0（arm64） | 不对公网开放 | 保存节点、隧道、转发和用户数据 |
| 节点端 | go-gost/gost、go-gost/x | 按节点配置 | 执行隧道和端口转发 |
| 内网接入端 | 同一 GOST Agent 的 connector 角色 | 主动出站 | 建立反向 TCP 会话并访问内网目标 |

## 面板服务器要求

### 最低配置

- 操作系统：64 位 Linux，推荐 Ubuntu 22.04/24.04 或 Debian 12
- 架构：`x86_64/amd64` 或 `aarch64/arm64`
- CPU：1 核
- 内存：1 GB，并配置至少 2 GB Swap
- 磁盘：8 GB 可用空间
- 权限：`root`，或能够使用 `sudo`
- 软件：Docker Engine 24+、Docker Compose v2、`curl`、`tar`、`flock`（通常由 util-linux 提供）
- 网络：能够访问 GitHub、GHCR 和 Docker Hub

### 推荐配置

- CPU：2 核或更多
- 内存：2 GB 或更多
- 磁盘：12 GB SSD 或更多
- 使用独立公网 IP 或域名，并通过 Nginx/Caddy 配置 HTTPS

最低配置保留全部面板功能，但适合节点和并发量较少的部署。更多节点、实时连接、高频主动健康检查或大量并发转发管理请求需要提高 CPU 和内存。低于 1 GB 内存不作为正式支持配置；`768 MB + 2 GB Swap` 只能用于小规模测试。

### 为什么改为预构建镜像

早期一键安装和在线更新会直接在面板服务器上执行 Maven、JDK、Node.js 和 Vite 编译。日常运行所需内存并不高，但源码构建会产生明显的瞬时内存、Swap 和磁盘峰值，导致 1 GB 或部分 2 GB 服务器安装缓慢、构建被 OOM 终止，并积累大量 Docker Build Cache。

从 `2.2.0` 开始，GitHub Actions 会为 `amd64` 和 `arm64` 构建经过测试的版本化镜像。一键安装与在线更新只在服务器上执行镜像拉取、容器启动和健康检查，不再安装 Maven/npm 依赖或编译源码。该变化不删除任何面板功能：MySQL、节点 WebSocket、主动健康检查、多级隧道、线路组、用户权限和在线更新仍完整保留。

为适配低内存服务器，默认运行参数同时调整为：

- Java 堆：`64M` 初始、`256M` 上限，并使用 Serial GC
- 数据库连接池：最低 `1`、最高 `10`
- Tomcat：最高 `100` 个工作线程、`512` 个连接
- MySQL：最高 `200` 个连接、`128M` InnoDB Buffer Pool
- 后端日志：单文件 `50 MB`、保留 `30` 天、压缩归档，总量不超过 `1 GB`

这些参数降低默认并发容量，不改变功能。可以在 `/etc/flux-panel/flux-panel.env` 中按服务器规模提高参数。

一键安装脚本正式支持 `amd64` 和 `arm64`。新安装会按服务器架构选择数据库镜像：

- `amd64` 使用 `mysql:5.7`
- `arm64` 使用 `mysql:8.0`

所选镜像会写入 `/etc/flux-panel/flux-panel.env` 的 `MYSQL_IMAGE`，后续更新和重新安装都会复用该值。已有 amd64 安装未配置 `MYSQL_IMAGE` 时仍默认使用 MySQL 5.7，不会因代码更新自动升级数据库版本。

### 端口与防火墙

| 端口 | 默认值 | 建议 |
| --- | ---: | --- |
| 面板 Web | `6366/TCP` | 对管理员开放；推荐通过 HTTPS 反向代理访问 |
| 后端/节点连接 | `6365/TCP` | 仅对节点 IP 和可信管理地址开放 |
| MySQL | 容器内部 | 不要映射到公网 |
| 节点转发端口 | 自定义 | 根据实际隧道和转发规则按需开放 TCP/UDP |
| 服务发布控制端口 | 端口池配置 | 仅允许内网接入端访问，建议配置来源 IP 白名单 |
| 服务发布租用端口 | 端口池范围 | 按实际发布服务对公网开放 TCP |

安装前请确认 `6365`、`6366` 未被其他程序占用。可以通过环境变量修改，示例见下文。

## 被控节点服务器要求

- 64 位 Linux，支持 `amd64` 或 `arm64`
- 使用 systemd 或 OpenRC 管理服务；Alpine Linux 使用 OpenRC
- 拥有 root 权限
- 能够通过 TCP 访问面板后端地址和端口，默认是 `面板IP:6365`
- 根据节点配置开放入口端口范围及业务所需的 TCP/UDP 防火墙规则
- 系统时间和 DNS 解析正常，建议启用 NTP
- 节点上的监听端口不能与已有服务冲突
- 建议至少 1 核 CPU、256 MB 可用内存；高并发或高带宽业务需要提高配置

节点无需安装 Docker。节点添加完成后，在“添加节点”页面点击“安装”，使用面板生成的命令安装节点组件。

节点安装脚本会自动识别服务管理器：

- Ubuntu、Debian、CentOS 等 systemd 系统创建 `/etc/systemd/system/gost.service`
- Alpine Linux 创建 `/etc/init.d/gost`，并通过 `rc-update` 加入默认启动级别

Alpine 精简系统如果尚未安装 OpenRC，请先执行 `apk add --no-cache openrc curl`。安装后的状态和日志可以通过 `rc-service gost status`、`tail -f /var/log/gost.log` 查看。

Agent 在监听 UDP 的 IPv6 通配地址时，如果节点内核未启用 IPv6，会自动回退到 IPv4 通配地址 `0.0.0.0`。IPv6 可用的节点仍保持双栈监听。

节点安装命令可以安全地重复执行。脚本会先停止已有服务，并只清理可执行文件指向 `/etc/gost/gost` 的残留进程及失效 PID 文件，再安装当前版本。

## 一键安装面板

先安装并启动 Docker Engine 与 Docker Compose v2，然后执行：

尚未安装 Docker 的 Ubuntu/Debian 服务器，可以使用 Docker 官方安装脚本：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
docker version
docker compose version
```

生产环境也可以按照 [Docker Engine 官方文档](https://docs.docker.com/engine/install/) 配置软件源安装。请不要同时混用发行版自带的旧版 `docker-compose` 和 Compose v2。

确认环境正常后安装 Flux Panel：

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh | sudo bash -s -- install
```

脚本会：

1. 检查操作系统、架构、Docker 和 Compose 环境。
2. 下载本仓库 `main` 分支源码到 `/opt/flux-panel`。
3. 在 `/etc/flux-panel/flux-panel.env` 生成数据库密码、JWT 密钥，并固定数据库镜像和面板版本。
4. 从 `ghcr.io/norwayxz` 拉取当前版本的 amd64/arm64 前端和后端镜像。
5. 启动 MySQL、后端和前端，并等待健康检查通过。

首次拉取通常需要数分钟，具体时间取决于服务器网络速度，不会在本机运行 Maven 或 Vite 编译。

一键安装使用固定容器名 `gost-mysql`、`springboot-backend`、`vite-frontend`，以及固定数据卷名 `mysql_data`、`backend_logs`。如果服务器上已有同名容器，脚本会停止安装，避免覆盖现有服务。

安装完成后访问：

```text
http://服务器IP:6366
```

默认账号：

```text
用户名：admin_user
密码：admin_user
```

首次登录后必须立即修改默认密码。

### 自定义端口安装

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh \
  | sudo env FLUX_PANEL_FRONTEND_PORT=8080 FLUX_PANEL_BACKEND_PORT=8081 bash -s -- install
```

### 调整运行内存与并发

安装后可以编辑 `/etc/flux-panel/flux-panel.env`。例如在 2 GB 以上服务器提高后端内存和连接池：

```dotenv
JAVA_OPTS="-Xms128m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
DB_POOL_MIN_IDLE=2
DB_POOL_MAX_SIZE=20
TOMCAT_MAX_THREADS=200
TOMCAT_MAX_CONNECTIONS=1000
MYSQL_MAX_CONNECTIONS=500
MYSQL_BUFFER_POOL_SIZE=256M
```

修改后执行 `sudo /usr/local/sbin/flux-panel-manager update`，或者等待下一次在线更新应用配置。

## 更新

管理员可以在“检查更新”页面查看 GitHub 版本。通过一键脚本安装或升级过的面板会同时安装受限的宿主机更新服务；发现新版本后，管理员可点击“立即更新”，页面会展示排队、构建、重启和健康检查状态。

在线更新只允许拉取本仓库固定的 `main` 分支和其中声明的版本化镜像，面板容器不会挂载 Docker Socket，也不能提交任意宿主机命令。更新过程会保留数据库卷；新镜像启动失败时，脚本会恢复上一版配置和镜像。

仍可使用命令行更新：

更新前先执行数据库备份，然后运行：

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh | sudo bash -s -- update
```

脚本会下载最新部署文件并拉取对应版本镜像。拉取、启动或健康检查失败时会恢复上一版；成功后仅保留当前版和上一版应用镜像用于回滚。涉及数据库结构变化的版本，请先阅读 Release 说明并执行对应迁移。

在线更新服务使用以下宿主机文件：

- `/usr/local/sbin/flux-panel-manager`：固定参数的面板管理脚本
- `/usr/local/sbin/flux-panel-update-worker`：受 systemd 调用的更新任务
- `/var/lib/flux-panel-updater`：更新请求、状态和最近一次日志

普通用户无权读取状态或提交更新任务。

## 一键卸载

### 卸载程序但保留数据

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh | sudo bash -s -- uninstall
```

该命令删除应用容器和 `/opt/flux-panel` 部署文件，但保留：

- MySQL 数据卷 `mysql_data`
- 后端日志卷 `backend_logs`
- `/etc/flux-panel/flux-panel.env` 中的数据库密码和密钥

再次执行一键安装命令，会复用保留的数据和密钥。

### 彻底卸载并永久删除数据

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh \
  | sudo env FLUX_PANEL_PURGE=1 bash -s -- purge
```

此命令会永久删除容器、数据库卷、日志卷、源码和密钥，无法恢复。执行前务必备份。

## 状态与日志

查看服务状态：

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh | sudo bash -s -- status
```

查看后端日志：

```bash
docker logs -f --tail 200 springboot-backend
```

查看前端日志：

```bash
docker logs -f --tail 200 vite-frontend
```

查看数据库日志：

```bash
docker logs -f --tail 200 gost-mysql
```

## 备份与恢复

读取数据库配置：

```bash
sudo cat /etc/flux-panel/flux-panel.env
```

备份数据库：

```bash
sudo bash -c 'set -a; source /etc/flux-panel/flux-panel.env; set +a; \
  docker exec gost-mysql mysqldump -u"$DB_USER" -p"$DB_PASSWORD" \
  --single-transaction --routines --triggers "$DB_NAME"' > flux-panel-backup.sql
```

恢复数据库前请停止业务写入，并确认备份文件来源可信：

```bash
sudo bash -c 'set -a; source /etc/flux-panel/flux-panel.env; set +a; \
  docker exec -i gost-mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME"' \
  < flux-panel-backup.sql
```

建议同时备份：

- `/etc/flux-panel/flux-panel.env`
- 数据库 SQL 文件
- 当前 Git 提交或 Release 版本号
- 反向代理与防火墙配置

## 从旧版升级

旧版升级前必须备份数据库。较早版本需要补充多级隧道字段：

```sql
ALTER TABLE `tunnel` ADD COLUMN `node_path` LONGTEXT DEFAULT NULL AFTER `out_ip`;
ALTER TABLE `forward` ADD COLUMN `hop_ports` LONGTEXT DEFAULT NULL AFTER `out_port`;
```

升级线路组、主动健康检查和协议模式时执行：

```bash
cd /opt/flux-panel
sudo bash -c 'set -a; source /etc/flux-panel/flux-panel.env; set +a; \
  docker exec -i gost-mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME"' \
  < migrations/20260724_forward_routing.sql
```

迁移文件：[migrations/20260724_forward_routing.sql](migrations/20260724_forward_routing.sql)。不要重复执行非幂等迁移；请先确认字段是否已经存在。

升级服务发布与端口租约时可以手动执行：

```bash
cd /opt/flux-panel
sudo bash -c 'set -a; source /etc/flux-panel/flux-panel.env; set +a; \
  docker exec -i gost-mysql mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME"' \
  < migrations/20260725_service_publishing.sql
```

正常在线更新不要求手动执行，后端启动时会幂等创建这些新增表。

## 手动构建

一键安装默认使用预构建镜像。只有开发、审计或修改源码时才需要执行以下源码构建方式，它仍会消耗较多内存和磁盘：

```bash
git clone https://github.com/NorwayXZ/flux-panel.git
cd flux-panel
sudo mkdir -p /etc/flux-panel
sudo cp .env.example /etc/flux-panel/flux-panel.env
sudo editor /etc/flux-panel/flux-panel.env
docker compose --env-file /etc/flux-panel/flux-panel.env \
  -f docker-compose-build.yml up -d --build
```

手动部署到 ARM64 时，将环境文件中的数据库镜像改为：

```dotenv
MYSQL_IMAGE=mysql:8.0
```

也可以分别构建：

```bash
docker build -t flux-panel-backend:local ./springboot-backend
docker build -t flux-panel-frontend:local ./vite-frontend
```

## 安全建议

- 首次登录后立即修改默认管理员密码
- 使用 HTTPS，不要长期通过明文 HTTP 登录
- 后端端口仅允许节点 IP 和可信管理地址访问
- 不要将 MySQL 暴露到公网
- 定期备份数据库和 `/etc/flux-panel/flux-panel.env`
- 不要在聊天、工单或公开仓库中泄露节点密钥、数据库密码和 JWT 密钥
- 删除节点、隧道或执行彻底卸载前确认级联影响

## 项目目录

```text
springboot-backend/       Spring Boot 后端
vite-frontend/            React 管理界面
go-gost/                  节点组件源码
migrations/               数据库迁移
scripts/flux-panel.sh     安装、更新、卸载和状态脚本
docker-compose.yml        预构建镜像部署配置
docker-compose-source.yml 旧版更新服务兼容入口
docker-compose-build.yml  开发用源码构建配置
VERSION                   面板与镜像统一版本号
gost.sql                  新安装数据库结构
```

## 上游与许可

本项目基于：

- [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel)
- [go-gost/gost](https://github.com/go-gost/gost)
- [go-gost/x](https://github.com/go-gost/x)

许可证见 [LICENSE](LICENSE)。
