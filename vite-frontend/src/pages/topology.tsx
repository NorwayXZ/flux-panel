import { useCallback, useEffect, useMemo, useState } from 'react';
import dagre from '@dagrejs/dagre';
import {
  Background,
  Controls,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Spinner } from '@heroui/spinner';
import { Tab, Tabs } from '@heroui/tabs';
import {
  Cable,
  CircleUserRound,
  Cloud,
  ExternalLink,
  GitBranch,
  Globe2,
  Network,
  RadioTower,
  RefreshCw,
  Server,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';

import { getTopologyGraph, type TopologyResourceNode } from '@/api';

const nodeWidth = 220;
const nodeHeight = 82;

const statusMeta = {
  healthy: { label: '正常', dot: 'bg-success', border: 'border-success/35' },
  degraded: { label: '注意', dot: 'bg-warning', border: 'border-warning/45' },
  offline: { label: '离线', dot: 'bg-danger', border: 'border-danger/50' },
  failed: { label: '失败', dot: 'bg-danger', border: 'border-danger/50' },
  paused: { label: '暂停', dot: 'bg-default-400', border: 'border-default-300' },
} as const;

const iconFor = (type: TopologyResourceNode['type']) => {
  const props = { size: 17, 'aria-hidden': true } as const;
  if (type === 'user') return <CircleUserRound {...props} />;
  if (type === 'domain') return <Globe2 {...props} />;
  if (type === 'forward') return <GitBranch {...props} />;
  if (type === 'tunnel') return <Cable {...props} />;
  if (type === 'node') return <Server {...props} />;
  if (type === 'connector') return <RadioTower {...props} />;
  if (type === 'mapping') return <Network {...props} />;
  return <Cloud {...props} />;
};

function ResourceNode({ data }: NodeProps<Node<TopologyResourceNode>>) {
  const meta = statusMeta[data.status] || statusMeta.paused;
  return (
    <div className={`h-[82px] w-[220px] rounded-md border bg-content1 px-3 py-2 shadow-sm ${meta.border}`}>
      <Handle type="target" position={Position.Left} className="!h-2 !w-2 !border-content1 !bg-default-400" />
      <div className="flex min-w-0 items-center gap-2">
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-default-100 text-default-600">{iconFor(data.type)}</span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold">{data.label}</div>
          <div className="truncate text-xs text-default-500">{data.subtitle}</div>
        </div>
        <span className={`h-2 w-2 shrink-0 rounded-full ${meta.dot}`} title={meta.label} />
      </div>
      <div className="mt-2 flex items-center justify-between text-[11px] text-default-500">
        <span className="uppercase">{data.type}</span>
        <span className="flex items-center gap-1">打开 <ExternalLink size={11} /></span>
      </div>
      <Handle type="source" position={Position.Right} className="!h-2 !w-2 !border-content1 !bg-default-400" />
    </div>
  );
}

const nodeTypes = { resource: ResourceNode };

function layoutGraph(nodes: Node<TopologyResourceNode>[], edges: Edge[]) {
  const graph = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
  graph.setGraph({ rankdir: 'LR', ranksep: 90, nodesep: 34, marginx: 30, marginy: 30 });
  nodes.forEach(node => graph.setNode(node.id, { width: nodeWidth, height: nodeHeight }));
  edges.forEach(edge => graph.setEdge(edge.source, edge.target));
  dagre.layout(graph);
  return nodes.map(node => {
    const point = graph.node(node.id);
    return { ...node, position: { x: point.x - nodeWidth / 2, y: point.y - nodeHeight / 2 } };
  });
}

function TopologyCanvas() {
  const navigate = useNavigate();
  const flow = useReactFlow();
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState<'all' | 'abnormal'>('all');
  const [resourceNodes, setResourceNodes] = useState<TopologyResourceNode[]>([]);
  const [resourceEdges, setResourceEdges] = useState<Array<{ id: string; source: string; target: string; label: string; status: string; active: boolean }>>([]);
  const [summary, setSummary] = useState({ nodes: 0, links: 0, healthy: 0, abnormal: 0 });

  const load = useCallback(async () => {
    setLoading(true);
    const response = await getTopologyGraph();
    setLoading(false);
    if (response.code !== 0) return toast.error(response.msg || '加载拓扑失败');
    setResourceNodes(response.data.nodes || []);
    setResourceEdges(response.data.edges || []);
    setSummary(response.data.summary || { nodes: 0, links: 0, healthy: 0, abnormal: 0 });
  }, []);

  useEffect(() => { load(); }, [load]);

  const visible = useMemo(() => {
    if (mode === 'all') return new Set(resourceNodes.map(node => node.id));
    const abnormal = new Set(resourceNodes.filter(node => node.status !== 'healthy').map(node => node.id));
    let changed = true;
    while (changed) {
      changed = false;
      resourceEdges.forEach(edge => {
        if (abnormal.has(edge.target) && !abnormal.has(edge.source)) {
          abnormal.add(edge.source);
          changed = true;
        }
      });
    }
    return abnormal;
  }, [mode, resourceEdges, resourceNodes]);

  const edges = useMemo<Edge[]>(() => resourceEdges
    .filter(edge => visible.has(edge.source) && visible.has(edge.target))
    .map(edge => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label,
      animated: edge.active && edge.status === 'healthy',
      style: { stroke: ['offline', 'failed'].includes(edge.status) ? '#f43f5e' : edge.status === 'degraded' ? '#f59e0b' : edge.active ? '#3b82f6' : '#71717a', strokeWidth: edge.active ? 2 : 1.4 },
      labelStyle: { fill: 'currentColor', fontSize: 11 },
      labelBgStyle: { fill: 'var(--heroui-content1)', fillOpacity: 0.92 },
    })), [resourceEdges, visible]);

  const nodes = useMemo(() => layoutGraph(resourceNodes
    .filter(node => visible.has(node.id))
    .map(node => ({ id: node.id, type: 'resource', position: { x: 0, y: 0 }, data: node })), edges), [edges, resourceNodes, visible]);

  useEffect(() => {
    if (nodes.length === 0) return;
    const timer = window.setTimeout(() => flow.fitView({ padding: 0.16, duration: 300 }), 80);
    return () => window.clearTimeout(timer);
  }, [edges.length, flow, mode, nodes.length]);

  if (loading && resourceNodes.length === 0) return <div className="flex h-[70vh] items-center justify-center"><Spinner label="加载全链路拓扑" /></div>;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-divider pb-4">
        <div><p className="text-sm text-default-500">资源关系与故障定位</p><h1 className="mt-1 text-2xl font-semibold">全链路拓扑</h1></div>
        <div className="flex items-center gap-2">
          <Tabs size="sm" selectedKey={mode} onSelectionChange={key => setMode(String(key) as 'all' | 'abnormal')} aria-label="拓扑筛选">
            <Tab key="all" title="全部链路" />
            <Tab key="abnormal" title={`异常 ${summary.abnormal}`} />
          </Tabs>
          <Button isIconOnly variant="flat" aria-label="刷新拓扑" title="刷新拓扑" isLoading={loading} onPress={load}><RefreshCw size={18} /></Button>
        </div>
      </header>
      <div className="grid grid-cols-2 gap-px border-b border-divider bg-divider sm:grid-cols-4">
        {[['资源组件', summary.nodes], ['连接关系', summary.links], ['运行正常', summary.healthy], ['异常组件', summary.abnormal]].map(([label, value], index) => (
          <div key={String(label)} className="bg-background px-4 py-3"><div className="text-xs text-default-500">{label}</div><div className={`mt-1 text-xl font-semibold ${index === 3 && Number(value) > 0 ? 'text-danger' : ''}`}>{value}</div></div>
        ))}
      </div>
      <div className="relative min-h-[620px] flex-1 bg-default-50">
        {nodes.length === 0 ? (
          <div className="flex h-full min-h-[620px] flex-col items-center justify-center gap-3 text-default-500"><Network size={34} /><span>{mode === 'abnormal' ? '当前没有异常链路' : '暂无可展示的资源关系'}</span></div>
        ) : (
          <ReactFlow<Node<TopologyResourceNode>, Edge> className="h-full w-full" nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView minZoom={0.05} maxZoom={1.8} onNodeClick={(_, node) => navigate(String(node.data.path))} proOptions={{ hideAttribution: true }}>
            <Background gap={22} size={1} color="rgba(113,113,122,.22)" />
            <MiniMap pannable zoomable nodeColor={node => {
              const status = (node.data as unknown as TopologyResourceNode).status;
              return ['offline', 'failed'].includes(status) ? '#f43f5e' : status === 'degraded' ? '#f59e0b' : '#22c55e';
            }} />
            <Controls showInteractive={false} />
          </ReactFlow>
        )}
      </div>
      <div className="flex flex-wrap gap-2 border-t border-divider py-3">
        <Chip size="sm" variant="flat" color="success">正常</Chip><Chip size="sm" variant="flat" color="warning">注意</Chip><Chip size="sm" variant="flat" color="danger">异常</Chip><Chip size="sm" variant="flat">暂停</Chip>
      </div>
    </div>
  );
}

export default function TopologyPage() {
  return <ReactFlowProvider><TopologyCanvas /></ReactFlowProvider>;
}
