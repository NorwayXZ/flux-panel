# Flux Panel Enhanced

这是基于 [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel) 持续维护的增强版本。

项目保留原有 Flux Panel 的用户、节点、隧道、转发、限速和流量统计能力，并重点改善节点失效后的运维体验，以及多节点链路的管理与诊断。

## 当前增强

- 支持 `A-B-C`、`A-B-C-D-E` 等多跳隧道，不再限制为单一 `A-B` 链路
- 隧道和转发诊断显示每段延迟、丢包率及总延迟
- 节点离线时，关联隧道和转发卡片同步显示为异常状态
- 节点监控和隧道管理按状态分区，正常项目在前、异常项目在后
- 节点、隧道和转发在各自分区或分组内按创建时间倒序显示
- 转发管理按隧道归纳，隧道下集中展示对应转发
- 转发状态同时考虑服务状态和完整链路状态，避免链路异常时仍显示“正常”
- 删除失效节点时可级联清理关联隧道、转发、授权和限速数据
- 删除隧道时自动删除该隧道下的转发及关联数据
- 在线节点仍受删除保护，避免误删正常节点并连带清理业务
- 日志中的令牌、密钥和敏感参数会进行脱敏处理

## 技术组件

- 前端：React、TypeScript、Vite、HeroUI
- 后端：Spring Boot、MyBatis-Plus、MySQL
- 节点：基于 go-gost/gost 与 go-gost/x
- 部署：Docker Compose

## 构建

前端：

```bash
cd vite-frontend
npm ci
npm run build
```

后端：

```bash
docker build -t flux-panel-backend:local ./springboot-backend
```

节点：

```bash
cd go-gost
go test ./...
go build -o gost
```

前端镜像：

```bash
docker build -t flux-panel-frontend:local ./vite-frontend
```

## 数据库说明

全新安装可直接使用仓库根目录的 `gost.sql`。

从旧版升级时，需要先备份数据库，并为现有表补充以下字段：

```sql
ALTER TABLE `tunnel` ADD COLUMN `node_path` LONGTEXT DEFAULT NULL AFTER `out_ip`;
ALTER TABLE `forward` ADD COLUMN `hop_ports` LONGTEXT DEFAULT NULL AFTER `out_port`;
```

升级到线路组、主动健康检查和协议模式版本时，再执行：

```bash
mysql -u root -p gost < migrations/20260724_forward_routing.sql
```

本次升级增加：

- 单线路、主备切换和低延迟选路
- 候选线路与目标地址主动健康检查
- TCP、UDP、TCP + UDP 入口协议
- 端口段批量映射
- 当前实际线路、线路延迟和健康目标状态

生产升级前请同时备份数据库、Docker Compose 配置和当前镜像标签，以便回滚。

## 默认账号

- 用户名：`admin_user`
- 密码：`admin_user`

首次登录后请立即修改默认密码。

## 上游与许可

本项目基于以下开源项目：

- [bqlpfy/flux-panel](https://github.com/bqlpfy/flux-panel)
- [go-gost/gost](https://github.com/go-gost/gost)
- [go-gost/x](https://github.com/go-gost/x)

许可证见 [LICENSE](LICENSE)。使用本项目时请遵守所在地法律法规，仅用于合法、合规的网络管理和转发用途。
