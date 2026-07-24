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
- 侧边栏包含版本检查入口，便于确认当前版本状态

## 架构

| 组件 | 技术 | 默认端口 | 作用 |
| --- | --- | ---: | --- |
| Web 前端 | React、TypeScript、Vite、HeroUI | `6366` | 管理界面 |
| 后端 | Spring Boot、MyBatis-Plus | `6365` | API、节点连接与任务调度 |
| 数据库 | MySQL 5.7（amd64）/ MySQL 8.0（arm64） | 不对公网开放 | 保存节点、隧道、转发和用户数据 |
| 节点端 | go-gost/gost、go-gost/x | 按节点配置 | 执行隧道和端口转发 |

## 面板服务器要求

### 最低配置

- 操作系统：64 位 Linux，推荐 Ubuntu 22.04/24.04 或 Debian 12
- 架构：`x86_64/amd64` 或 `aarch64/arm64`
- CPU：2 核
- 内存：2 GB，首次源码构建建议至少配置 2 GB Swap
- 磁盘：10 GB 可用空间
- 权限：`root`，或能够使用 `sudo`
- 软件：Docker Engine 24+、Docker Compose v2、`curl`、`tar`
- 网络：能够访问 GitHub、Docker Hub、Maven Central 和 npm 软件源

### 推荐配置

- CPU：4 核
- 内存：4 GB 或更多
- 磁盘：20 GB SSD 或更多
- 使用独立公网 IP 或域名，并通过 Nginx/Caddy 配置 HTTPS

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

安装前请确认 `6365`、`6366` 未被其他程序占用。可以通过环境变量修改，示例见下文。

## 被控节点服务器要求

- 64 位 Linux，支持 `amd64` 或 `arm64`
- 使用 systemd 管理服务
- 拥有 root 权限
- 能够通过 TCP 访问面板后端地址和端口，默认是 `面板IP:6365`
- 根据节点配置开放入口端口范围及业务所需的 TCP/UDP 防火墙规则
- 系统时间和 DNS 解析正常，建议启用 NTP
- 节点上的监听端口不能与已有服务冲突
- 建议至少 1 核 CPU、256 MB 可用内存；高并发或高带宽业务需要提高配置

节点无需安装 Docker。节点添加完成后，在“添加节点”页面点击“安装”，使用面板生成的命令安装节点组件。

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
3. 在 `/etc/flux-panel/flux-panel.env` 生成数据库密码、JWT 密钥，并固定与服务器架构匹配的数据库镜像。
4. 从源码构建前端与后端镜像。
5. 启动 MySQL、后端和前端，并等待健康检查通过。

首次构建通常需要数分钟，具体时间取决于服务器性能和网络速度。

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

## 更新

更新前先执行数据库备份，然后运行：

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh | sudo bash -s -- update
```

脚本会下载最新源码并重新构建容器。构建失败时会恢复上一份源码。涉及数据库结构变化的版本，请先阅读 Release 说明并执行对应迁移。

## 一键卸载

### 卸载程序但保留数据

```bash
curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh | sudo bash -s -- uninstall
```

该命令删除应用容器和 `/opt/flux-panel` 源码，但保留：

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

## 手动构建

```bash
git clone https://github.com/NorwayXZ/flux-panel.git
cd flux-panel
sudo mkdir -p /etc/flux-panel
sudo cp .env.example /etc/flux-panel/flux-panel.env
sudo editor /etc/flux-panel/flux-panel.env
docker compose --env-file /etc/flux-panel/flux-panel.env \
  -f docker-compose-source.yml up -d --build
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
docker-compose-source.yml 源码构建部署配置
gost.sql                  新安装数据库结构
```

## 上游与许可

本项目基于：

- [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel)
- [go-gost/gost](https://github.com/go-gost/gost)
- [go-gost/x](https://github.com/go-gost/x)

许可证见 [LICENSE](LICENSE)。
