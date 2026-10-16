import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import {
  Archive,
  Boxes,
  Copy,
  ExternalLink,
  Play,
  RefreshCw,
  RotateCcw,
  Search,
  Square,
  Server,
  Trash2,
  TriangleAlert,
  UploadCloud,
} from "lucide-react";
import toast from "react-hot-toast";

import {
  deployDockerApp,
  getDnsZoneOptions,
  getDockerAppCommand,
  getDockerAppEvents,
  getDockerAppOverview,
  inspectDockerNode,
  runDockerAppAction,
  type DockerAppEvent,
  type DockerAppInstance,
  type DockerAppOverview,
  type DockerContainerInfo,
  type DnsZoneOption,
} from "@/api";
import PageShell from "@/components/page-shell";
import PageHelp from "@/components/page-help";
import ResourceCard, { ResourceCardGrid } from "@/components/resource-card";
import StatsCard from "@/components/ui/stats-card";
import { formatDateTime } from "@/utils/format";

const empty: DockerAppOverview = {
  nodes: [],
  templates: [],
  apps: [],
  summary: { apps: 0, active: 0, errors: 0, dockerReadyNodes: 0 },
  minimumAgentVersion: "2.47.0",
};
const safeOverview = (
  value?: Partial<DockerAppOverview> | null,
): DockerAppOverview => ({
  nodes: Array.isArray(value?.nodes) ? value.nodes : [],
  templates: Array.isArray(value?.templates) ? value.templates : [],
  apps: Array.isArray(value?.apps) ? value.apps : [],
  summary: {
    apps: value?.summary?.apps || 0,
    active: value?.summary?.active || 0,
    errors: value?.summary?.errors || 0,
    dockerReadyNodes: value?.summary?.dockerReadyNodes || 0,
  },
  minimumAgentVersion: value?.minimumAgentVersion || empty.minimumAgentVersion,
});

const stateMeta = (state: string) => {
  if (state === "active") return { label: "运行中", color: "success" as const };
  if (state === "error") return { label: "异常", color: "danger" as const };
  if (state === "delete_pending")
    return { label: "删除中", color: "warning" as const };
  if (state === "operating" || state === "provisioning")
    return { label: "处理中", color: "primary" as const };

  return { label: state || "未知", color: "default" as const };
};
const timeText = (value?: number) => formatDateTime(value, "-");

export default function DockerAppsPage() {
  const [data, setData] = useState<DockerAppOverview>(empty);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [inspectOpen, setInspectOpen] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [eventsOpen, setEventsOpen] = useState(false);
  const [containers, setContainers] = useState<DockerContainerInfo[]>([]);
  const [events, setEvents] = useState<DockerAppEvent[]>([]);
  const [dnsZones, setDnsZones] = useState<DnsZoneOption[]>([]);
  const [command, setCommand] = useState("");
  const [loadError, setLoadError] = useState("");
  const [form, setForm] = useState({
    nodeId: "",
    templateId: "",
    name: "",
    containerName: "",
    hostPort: "",
    bindDomain: false,
    domain: "",
    dnsZoneId: "",
    entryNodeId: "",
    listenPort: "443",
    pathPrefix: "/",
    backendPath: "/",
  });

  const selectedTemplate = useMemo(
    () => data.templates.find((item) => item.id === form.templateId),
    [data.templates, form.templateId],
  );

  const load = async (quiet = false) => {
    if (!quiet) setLoading(true);
    const [overviewResult, zonesResult] = await Promise.allSettled([
      getDockerAppOverview(),
      getDnsZoneOptions(),
    ]);

    if (
      overviewResult.status === "fulfilled" &&
      overviewResult.value.code === 0
    ) {
      setData(safeOverview(overviewResult.value.data));
      setLoadError("");
    } else {
      const message =
        overviewResult.status === "fulfilled"
          ? overviewResult.value.msg
          : "加载 Docker 应用中心失败";

      setLoadError(message || "加载 Docker 应用中心失败");
      if (!quiet) toast.error(message || "加载 Docker 应用中心失败");
    }
    if (zonesResult.status === "fulfilled" && zonesResult.value.code === 0) {
      setDnsZones(zonesResult.value.data || []);
    } else if (!quiet) {
      toast.error("DNS 域名配置加载失败，域名绑定可稍后再试");
    }
    if (!quiet) setLoading(false);
  };

  useEffect(() => {
    void load();
  }, []);

  const openCreate = () => {
    const template = data.templates[0];
    const node =
      data.nodes.find((item) => item.online && item.compatible) ||
      data.nodes[0];

    setForm({
      nodeId: node ? String(node.id) : "",
      templateId: template?.id || "",
      name: template?.name || "",
      containerName: template ? `flux-${template.id}` : "",
      hostPort: template ? String(template.defaultHostPort) : "",
      bindDomain: false,
      domain: "",
      dnsZoneId: "",
      entryNodeId: node ? String(node.id) : "",
      listenPort: "443",
      pathPrefix: "/",
      backendPath: "/",
    });
    setCreateOpen(true);
  };

  const deploy = async () => {
    if (!form.nodeId || !form.templateId || !form.name.trim())
      return toast.error("请选择节点、应用模板并填写名称");
    if (form.bindDomain && (!form.domain.trim() || !form.dnsZoneId))
      return toast.error("请填写访问域名并选择 DNS 域名配置");
    setBusy("deploy");
    const response = await deployDockerApp({
      nodeId: Number(form.nodeId),
      templateId: form.templateId,
      name: form.name.trim(),
      containerName: form.containerName.trim() || undefined,
      hostPort: form.hostPort ? Number(form.hostPort) : undefined,
      bindDomain: form.bindDomain,
      domain: form.bindDomain ? form.domain.trim() : undefined,
      dnsZoneId: form.bindDomain ? Number(form.dnsZoneId) : undefined,
      entryNodeId:
        form.bindDomain && form.entryNodeId
          ? Number(form.entryNodeId)
          : undefined,
      listenPort: form.bindDomain ? Number(form.listenPort || 443) : undefined,
      pathPrefix: form.bindDomain ? form.pathPrefix || "/" : undefined,
      backendPath: form.bindDomain ? form.backendPath || "/" : undefined,
    });

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "部署失败");
    setData(safeOverview(response.data));
    setCreateOpen(false);
    toast.success("部署任务已提交");
  };

  const inspect = async (nodeId: number) => {
    setBusy(`inspect-${nodeId}`);
    const response = await inspectDockerNode(nodeId);

    setBusy("");
    if (response.code !== 0)
      return toast.error(response.msg || "Docker 检查失败");
    setContainers(response.data.containers || []);
    setInspectOpen(true);
  };

  const action = async (
    app: DockerAppInstance,
    name: "upgrade" | "backup" | "stop" | "start" | "remove" | "rollback",
  ) => {
    setBusy(`${name}-${app.id}`);
    const response = await runDockerAppAction(app.id, name);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "操作失败");
    setData(safeOverview(response.data));
    toast.success("操作已提交");
  };

  const showCommand = async (
    app: DockerAppInstance,
    name: "upgrade" | "backup" | "stop" | "start" | "remove" | "rollback",
  ) => {
    setBusy(`cmd-${app.id}`);
    const response = await getDockerAppCommand(app.id, name);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "生成命令失败");
    setCommand(response.data);
    setCommandOpen(true);
  };

  const showEvents = async (app: DockerAppInstance) => {
    setBusy(`events-${app.id}`);
    const response = await getDockerAppEvents(app.id);

    setBusy("");
    if (response.code !== 0) return toast.error(response.msg || "加载事件失败");
    setEvents(response.data || []);
    setEventsOpen(true);
  };

  const copy = async () => {
    await navigator.clipboard.writeText(command);
    toast.success("已复制命令");
  };

  if (loading)
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Spinner label="加载 Docker 应用中心" />
      </div>
    );

  return (
    <PageShell className="space-y-6" width="wide">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-1.5">
            <h1 className="text-2xl font-bold text-foreground">Docker 应用</h1>
            <PageHelp>识别节点 Docker，一键部署常用应用，并保留升级、备份、迁移和回退操作记录。</PageHelp>
          </div>
        </div>
        <div className="flex gap-2">
          <Button
            isIconOnly
            title="刷新"
            variant="flat"
            onPress={() => void load(true)}
          >
            <RefreshCw size={17} />
          </Button>
          <Button
            color="primary"
            startContent={<UploadCloud size={17} />}
            onPress={openCreate}
          >
            部署应用
          </Button>
        </div>
      </header>
      {loadError && (
        <div className="rounded-lg border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-800 dark:border-warning-500/20 dark:bg-warning-500/10 dark:text-warning-200">
          {loadError}；已保留上一次可用内容。
        </div>
      )}

      <section className="grid grid-cols-2 gap-3 xl:grid-cols-4">
        <StatsCard
          color="primary"
          icon={<Boxes size={20} />}
          title="应用数量"
          value={data.summary.apps}
        />
        <StatsCard
          color="success"
          icon={<Play size={20} />}
          title="运行中"
          value={data.summary.active}
        />
        <StatsCard
          color="danger"
          icon={<TriangleAlert size={20} />}
          title="异常"
          value={data.summary.errors}
        />
        <StatsCard
          color="secondary"
          icon={<Server size={20} />}
          title="可部署节点"
          value={data.summary.dockerReadyNodes}
        />
      </section>

      <section className="space-y-5">
        <section className="space-y-3">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div className="flex items-center gap-2">
              <Search className="text-primary" size={18} />
              <div>
                <h2 className="text-base font-semibold">Docker 节点</h2>
                <p className="mt-1 text-xs text-default-500">
                  识别状态、Agent 版本和可执行能力
                </p>
              </div>
            </div>
            <Chip size="sm" variant="flat">
              {data.nodes.length} 个节点
            </Chip>
          </div>
          <ResourceCardGrid>
            {data.nodes.map((node) => (
              <ResourceCard
                key={node.id}
                actions={[
                  {
                    label: "识别 Docker",
                    icon: <Search size={14} />,
                    isDisabled: !node.online,
                    isLoading: busy === `inspect-${node.id}`,
                    onPress: () => void inspect(node.id),
                  },
                ]}
                facts={[
                  { label: "Agent 版本", value: node.version || "-" },
                  {
                    label: "最低要求",
                    value: `${data.minimumAgentVersion}+`,
                  },
                ]}
                notice={
                  node.compatible
                    ? undefined
                    : {
                        icon: <TriangleAlert size={14} />,
                        label: "需要升级 Agent",
                        value: "升级后才能直接执行应用动作，现可使用手动命令",
                        tone: "warning",
                      }
                }
                status={
                  node.online
                    ? { text: "在线", color: "success" }
                    : { text: "离线", color: "default" }
                }
                title={node.name}
              />
            ))}
          </ResourceCardGrid>
        </section>

        <section className="space-y-3">
          <div className="flex flex-wrap items-end justify-between gap-2">
            <div className="flex items-center gap-2">
              <Boxes className="text-secondary" size={18} />
              <div>
                <h2 className="text-base font-semibold">已部署应用</h2>
                <p className="mt-1 text-xs text-default-500">
                  集中查看访问信息、备份状态和应用操作
                </p>
              </div>
            </div>
            <Chip size="sm" variant="flat">
              {data.apps.length} 个应用
            </Chip>
          </div>
          {data.apps.length === 0 ? (
            <div className="flex min-h-40 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-divider bg-content1 text-default-400">
              <Boxes size={32} />
              <p>还没有 Docker 应用</p>
            </div>
          ) : (
            <ResourceCardGrid>
              {data.apps.map((app) => {
                const meta = stateMeta(app.state);
                const running = app.state === "active";
                const toggleAction = running ? "stop" : "start";

                return (
                  <ResourceCard
                    key={app.id}
                    actions={[
                      {
                        label: "升级",
                        icon: <UploadCloud size={14} />,
                        isLoading: busy === `upgrade-${app.id}`,
                        onPress: () => void action(app, "upgrade"),
                      },
                      {
                        label: "备份",
                        icon: <Archive size={14} />,
                        isLoading: busy === `backup-${app.id}`,
                        onPress: () => void action(app, "backup"),
                      },
                      {
                        label: "回退",
                        icon: <RotateCcw size={14} />,
                        isLoading: busy === `rollback-${app.id}`,
                        onPress: () => void action(app, "rollback"),
                      },
                      {
                        label: "删除",
                        icon: <Trash2 size={14} />,
                        color: "danger",
                        isLoading: busy === `remove-${app.id}`,
                        onPress: () => void action(app, "remove"),
                      },
                    ]}
                    blocks={[
                      {
                        label: "访问",
                        rows: [
                          {
                            label: "端口",
                            value: `${app.hostPort}:${app.containerPort}`,
                          },
                          {
                            label: "域名",
                            value: app.domain || "未绑定",
                            title: app.domain || undefined,
                          },
                        ],
                      },
                      {
                        label: "容器",
                        danger: app.state === "error",
                        rows: [
                          {
                            label: "镜像",
                            value: app.image,
                            title: app.image,
                          },
                          { label: "名称", value: app.containerName },
                        ],
                      },
                    ]}
                    chips={[{ text: app.templateId, color: "primary" }]}
                    danger={app.state === "error"}
                    facts={[
                      { label: "节点", value: app.nodeName || app.nodeId },
                      { label: "最近更新", value: timeText(app.updatedTime) },
                    ]}
                    headerExtra={
                      <div className="flex items-center">
                        <Button
                          isIconOnly
                          isLoading={busy === `${toggleAction}-${app.id}`}
                          size="sm"
                          title={running ? "停止" : "启动"}
                          variant="light"
                          onPress={() => void action(app, toggleAction)}
                        >
                          {running ? <Square size={14} /> : <Play size={15} />}
                        </Button>
                        <Button
                          isIconOnly
                          isLoading={busy === `cmd-${app.id}`}
                          size="sm"
                          title="手动命令"
                          variant="light"
                          onPress={() => void showCommand(app, "upgrade")}
                        >
                          <Copy size={15} />
                        </Button>
                        <Button
                          isIconOnly
                          isLoading={busy === `events-${app.id}`}
                          size="sm"
                          title="事件"
                          variant="light"
                          onPress={() => void showEvents(app)}
                        >
                          <ExternalLink size={15} />
                        </Button>
                      </div>
                    }
                    notice={
                      app.lastError
                        ? {
                            icon: <TriangleAlert size={14} />,
                            label: "最近错误",
                            value: (
                              <span title={app.lastError}>{app.lastError}</span>
                            ),
                            tone: "danger",
                          }
                        : app.backupPath
                          ? {
                              icon: <Archive size={14} />,
                              label: "最近备份",
                              value: (
                                <span title={app.backupPath}>
                                  {app.backupPath}
                                </span>
                              ),
                              tone: "success",
                            }
                          : undefined
                    }
                    status={{ text: meta.label, color: meta.color }}
                    title={app.name}
                  />
                );
              })}
            </ResourceCardGrid>
          )}
        </section>
      </section>

      <Modal isOpen={createOpen} size="2xl" onOpenChange={setCreateOpen}>
        <ModalContent>
          <ModalHeader>部署 Docker 应用</ModalHeader>
          <ModalBody className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Select
                label="节点"
                selectedKeys={form.nodeId ? [form.nodeId] : []}
                onSelectionChange={(keys) =>
                  setForm({
                    ...form,
                    nodeId: String(Array.from(keys)[0] || ""),
                  })
                }
              >
                {data.nodes.map((node) => (
                  <SelectItem key={String(node.id)} textValue={node.name}>
                    {node.name} · {node.online ? "在线" : "离线"}
                  </SelectItem>
                ))}
              </Select>
              <Select
                label="应用模板"
                selectedKeys={form.templateId ? [form.templateId] : []}
                onSelectionChange={(keys) => {
                  const id = String(Array.from(keys)[0] || "");
                  const template = data.templates.find(
                    (item) => item.id === id,
                  );

                  setForm({
                    ...form,
                    templateId: id,
                    name: template?.name || form.name,
                    containerName: template
                      ? `flux-${template.id}`
                      : form.containerName,
                    hostPort: template
                      ? String(template.defaultHostPort)
                      : form.hostPort,
                  });
                }}
              >
                {data.templates.map((template) => (
                  <SelectItem key={template.id} textValue={template.name}>
                    {template.name} · {template.image}
                  </SelectItem>
                ))}
              </Select>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                label="应用名称"
                value={form.name}
                onValueChange={(name) => setForm({ ...form, name })}
              />
              <Input
                label="容器名"
                value={form.containerName}
                onValueChange={(containerName) =>
                  setForm({ ...form, containerName })
                }
              />
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <Input
                label="公网端口"
                type="number"
                value={form.hostPort}
                onValueChange={(hostPort) => setForm({ ...form, hostPort })}
              />
              <Input
                isReadOnly
                label="容器端口"
                value={
                  selectedTemplate ? String(selectedTemplate.containerPort) : ""
                }
              />
            </div>
            <Switch
              isSelected={form.bindDomain}
              onValueChange={(bindDomain) => setForm({ ...form, bindDomain })}
            >
              同时绑定 HTTPS 域名
            </Switch>
            {form.bindDomain && (
              <div className="grid gap-4 border-y border-divider py-4 sm:grid-cols-2">
                <Input
                  label="访问域名"
                  placeholder="app.example.com"
                  value={form.domain}
                  onValueChange={(domain) => setForm({ ...form, domain })}
                />
                <Select
                  label="DNS 与证书配置"
                  selectedKeys={form.dnsZoneId ? [form.dnsZoneId] : []}
                  onSelectionChange={(keys) =>
                    setForm({
                      ...form,
                      dnsZoneId: String(Array.from(keys)[0] || ""),
                    })
                  }
                >
                  {dnsZones.map((zone) => (
                    <SelectItem key={String(zone.id)} textValue={zone.zoneName}>
                      {zone.zoneName} · {zone.accountName}
                    </SelectItem>
                  ))}
                </Select>
                <Select
                  label="HTTPS 入口节点"
                  selectedKeys={form.entryNodeId ? [form.entryNodeId] : []}
                  onSelectionChange={(keys) =>
                    setForm({
                      ...form,
                      entryNodeId: String(Array.from(keys)[0] || ""),
                    })
                  }
                >
                  {data.nodes.map((node) => (
                    <SelectItem key={String(node.id)} textValue={node.name}>
                      {node.name} · {node.serverIp || node.ip || "地址未知"}
                    </SelectItem>
                  ))}
                </Select>
                <Input
                  label="HTTPS 监听端口"
                  type="number"
                  value={form.listenPort}
                  onValueChange={(listenPort) =>
                    setForm({ ...form, listenPort })
                  }
                />
                <Input
                  label="外部访问路径"
                  value={form.pathPrefix}
                  onValueChange={(pathPrefix) =>
                    setForm({ ...form, pathPrefix })
                  }
                />
                <Input
                  label="后端根路径"
                  value={form.backendPath}
                  onValueChange={(backendPath) =>
                    setForm({ ...form, backendPath })
                  }
                />
              </div>
            )}
            <div className="border-y border-divider py-3 text-xs leading-5 text-default-500">
              旧 Agent
              不支持直接执行时，应用卡片会保留手动命令；开启域名绑定后，面板会自动创建托管
              HTTPS 入口并申请证书。
            </div>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "deploy"}
              onPress={() => void deploy()}
            >
              部署
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={inspectOpen}
        scrollBehavior="inside"
        size="4xl"
        onOpenChange={setInspectOpen}
      >
        <ModalContent>
          <ModalHeader>Docker 容器识别</ModalHeader>
          <ModalBody>
            {containers.length === 0 ? (
              <div className="py-16 text-center text-default-400">
                没有识别到运行容器
              </div>
            ) : (
              <div className="divide-y divide-divider">
                {containers.map((item) => (
                  <div
                    key={item.id || item.name}
                    className="grid gap-2 py-3 text-sm sm:grid-cols-[1fr_1fr_1fr]"
                  >
                    <div>
                      <p className="font-medium">{item.name}</p>
                      <p className="font-mono text-xs text-default-400">
                        {item.id}
                      </p>
                    </div>
                    <p className="break-all">{item.image}</p>
                    <p className="text-default-500">
                      {item.status || item.state}
                    </p>
                  </div>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button color="primary" onPress={() => setInspectOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal isOpen={commandOpen} size="2xl" onOpenChange={setCommandOpen}>
        <ModalContent>
          <ModalHeader>手动命令</ModalHeader>
          <ModalBody>
            <pre className="max-h-[55vh] overflow-auto whitespace-pre-wrap rounded-lg bg-default-100 p-4 font-mono text-sm">
              {command}
            </pre>
          </ModalBody>
          <ModalFooter>
            <Button variant="flat" onPress={() => setCommandOpen(false)}>
              关闭
            </Button>
            <Button
              color="primary"
              startContent={<Copy size={16} />}
              onPress={() => void copy()}
            >
              复制
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal
        isOpen={eventsOpen}
        scrollBehavior="inside"
        size="3xl"
        onOpenChange={setEventsOpen}
      >
        <ModalContent>
          <ModalHeader>应用事件</ModalHeader>
          <ModalBody>
            {events.length === 0 ? (
              <div className="py-16 text-center text-default-400">暂无事件</div>
            ) : (
              <div className="divide-y divide-divider">
                {events.map((event) => (
                  <div
                    key={event.id}
                    className="grid gap-2 py-3 text-sm sm:grid-cols-[140px_120px_1fr]"
                  >
                    <span>{timeText(event.createdTime)}</span>
                    <Chip
                      color={
                        event.status === "success"
                          ? "success"
                          : event.status === "failed"
                            ? "danger"
                            : "primary"
                      }
                      size="sm"
                      variant="flat"
                    >
                      {event.eventType}
                    </Chip>
                    <span className="text-default-500">
                      {event.detail || "-"}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button color="primary" onPress={() => setEventsOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </PageShell>
  );
}
