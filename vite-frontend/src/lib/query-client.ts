import { QueryClient } from "@tanstack/react-query";

let previousToken: string | null | undefined;
let sessionVersion = 0;

export function sessionQueryKey(key: readonly unknown[]) {
  const token =
    typeof window === "undefined" ? null : window.localStorage.getItem("token");

  if (token !== previousToken) {
    previousToken = token;
    sessionVersion++;
  }

  return [...key, { sessionVersion }] as const;
}

// 创建全局 QueryClient 实例
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 5秒内数据被认为是新鲜的，不会重新请求
      staleTime: 5000,

      // 缓存数据在5分钟后被垃圾回收
      gcTime: 5 * 60 * 1000,

      // 失败时重试2次
      retry: 2,

      // 窗口重新获得焦点时不自动刷新（避免频繁请求）
      refetchOnWindowFocus: false,

      // 网络重连时不自动刷新
      refetchOnReconnect: false,

      // 重新打开页面时刷新已过期的数据
      refetchOnMount: true,
    },
    mutations: {
      // Writes may already have succeeded when a response is lost; never replay automatically.
      retry: false,
    },
  },
});

// 导出查询键工厂函数，用于统一管理查询键
export const queryKeys = {
  // 节点相关
  nodes: {
    all: ["nodes"] as const,
    list: () => [...queryKeys.nodes.all, "list"] as const,
    detail: (id: number) => [...queryKeys.nodes.all, "detail", id] as const,
    terminal: (id: number) => [...queryKeys.nodes.all, "terminal", id] as const,
  },

  // 转发相关
  forwards: {
    all: ["forwards"] as const,
    list: () => [...queryKeys.forwards.all, "list"] as const,
    detail: (id: number) => [...queryKeys.forwards.all, "detail", id] as const,
  },

  // 隧道相关
  tunnels: {
    all: ["tunnels"] as const,
    list: () => [...queryKeys.tunnels.all, "list"] as const,
    detail: (id: number) => [...queryKeys.tunnels.all, "detail", id] as const,
  },

  // 用户相关
  users: {
    all: ["users"] as const,
    list: (filters?: unknown) =>
      [...queryKeys.users.all, "list", filters] as const,
    detail: (id: number) => [...queryKeys.users.all, "detail", id] as const,
    package: () => [...queryKeys.users.all, "package"] as const,
  },

  // 监控相关
  monitoring: {
    all: ["monitoring"] as const,
    overview: () => [...queryKeys.monitoring.all, "overview"] as const,
    alerts: () => [...queryKeys.monitoring.all, "alerts"] as const,
  },

  // 私有代理
  privateProxy: {
    all: ["privateProxy"] as const,
    list: () => [...queryKeys.privateProxy.all, "list"] as const,
  },

  // Docker 应用
  dockerApps: {
    all: ["dockerApps"] as const,
    instances: () => [...queryKeys.dockerApps.all, "instances"] as const,
    templates: () => [...queryKeys.dockerApps.all, "templates"] as const,
  },

  // 配置相关
  config: {
    all: ["config"] as const,
    get: (key: string) => [...queryKeys.config.all, key] as const,
  },
  authorizedEntries: {
    all: ["authorizedEntries"] as const,
    grants: (userId?: number) =>
      ["authorizedEntries", "grants", userId] as const,
    templates: () => ["authorizedEntries", "templates"] as const,
  },
};
