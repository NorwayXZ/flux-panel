import { useQuery, useMutation, useQueryClient, UseQueryOptions } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-client';
import * as api from '@/api';
import toast from 'react-hot-toast';

// ========== 节点相关 Hooks ==========

/**
 * 获取节点列表
 * 自动缓存8秒，每10秒自动刷新
 */
export function useNodes() {
  return useQuery({
    queryKey: queryKeys.nodes.list(),
    queryFn: async () => {
      const response = await api.getNodeList();
      if (response.code !== 0) {
        throw new Error(response.msg || '获取节点列表失败');
      }
      return response.data;
    },
    staleTime: 8000,
    refetchInterval: 10000, // 每10秒自动刷新
  });
}

/**
 * 创建节点
 */
export function useCreateNode() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (data: any) => {
      const response = await api.createNode(data);
      if (response.code !== 0) {
        throw new Error(response.msg || '创建节点失败');
      }
      return response.data;
    },
    onSuccess: () => {
      // 刷新节点列表
      queryClient.invalidateQueries({ queryKey: queryKeys.nodes.all });
      toast.success('节点创建成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

/**
 * 更新节点
 */
export function useUpdateNode() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (data: any) => {
      const response = await api.updateNode(data);
      if (response.code !== 0) {
        throw new Error(response.msg || '更新节点失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.nodes.all });
      toast.success('节点更新成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

/**
 * 删除节点
 */
export function useDeleteNode() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: number) => {
      const response = await api.deleteNode(id);
      if (response.code !== 0) {
        throw new Error(response.msg || '删除节点失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.nodes.all });
      toast.success('节点删除成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

// ========== 转发相关 Hooks ==========

/**
 * 获取转发列表
 */
export function useForwards() {
  return useQuery({
    queryKey: queryKeys.forwards.list(),
    queryFn: async () => {
      const response = await api.getForwardList();
      if (response.code !== 0) {
        throw new Error(response.msg || '获取转发列表失败');
      }
      return response.data;
    },
    staleTime: 5000,
    refetchInterval: 8000,
  });
}

/**
 * 创建转发
 */
export function useCreateForward() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (data: any) => {
      const response = await api.createForward(data);
      if (response.code !== 0) {
        throw new Error(response.msg || '创建转发失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.forwards.all });
      toast.success('转发创建成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

/**
 * 更新转发
 */
export function useUpdateForward() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (data: any) => {
      const response = await api.updateForward(data);
      if (response.code !== 0) {
        throw new Error(response.msg || '更新转发失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.forwards.all });
      toast.success('转发更新成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

/**
 * 删除转发
 */
export function useDeleteForward() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: number) => {
      const response = await api.deleteForward(id);
      if (response.code !== 0) {
        throw new Error(response.msg || '删除转发失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.forwards.all });
      toast.success('转发删除成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

// ========== 用户相关 Hooks ==========

/**
 * 获取用户列表
 */
export function useUsers(pageData?: any) {
  return useQuery({
    queryKey: queryKeys.users.list(pageData?.page),
    queryFn: async () => {
      const response = await api.getAllUsers(pageData);
      if (response.code !== 0) {
        throw new Error(response.msg || '获取用户列表失败');
      }
      return response.data;
    },
    staleTime: 10000,
  });
}

/**
 * 获取用户套餐信息
 */
export function useUserPackage() {
  return useQuery({
    queryKey: queryKeys.users.package(),
    queryFn: async () => {
      const response = await api.getUserPackageInfo();
      if (response.code !== 0) {
        throw new Error(response.msg || '获取套餐信息失败');
      }
      return response.data;
    },
    staleTime: 30000, // 套餐信息变化较少，缓存30秒
  });
}

/**
 * 创建用户
 */
export function useCreateUser() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (data: any) => {
      const response = await api.createUser(data);
      if (response.code !== 0) {
        throw new Error(response.msg || '创建用户失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.users.all });
      toast.success('用户创建成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

/**
 * 更新用户
 */
export function useUpdateUser() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (data: any) => {
      const response = await api.updateUser(data);
      if (response.code !== 0) {
        throw new Error(response.msg || '更新用户失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.users.all });
      toast.success('用户更新成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

/**
 * 删除用户
 */
export function useDeleteUser() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: number) => {
      const response = await api.deleteUser(id);
      if (response.code !== 0) {
        throw new Error(response.msg || '删除用户失败');
      }
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.users.all });
      toast.success('用户删除成功');
    },
    onError: (error: Error) => {
      toast.error(error.message);
    },
  });
}

// ========== 监控相关 Hooks ==========

/**
 * 获取监控概览
 */
export function useMonitoringOverview() {
  return useQuery({
    queryKey: queryKeys.monitoring.overview(),
    queryFn: async () => {
      const response = await api.getMonitoringOverview();
      if (response.code !== 0) {
        throw new Error(response.msg || '获取监控概览失败');
      }
      return response.data;
    },
    staleTime: 10000,
    refetchInterval: 15000, // 每15秒刷新
  });
}

/**
 * 获取监控告警
 */
export function useMonitoringAlerts() {
  return useQuery({
    queryKey: queryKeys.monitoring.alerts(),
    queryFn: async () => {
      const response = await api.getMonitoringAlerts();
      if (response.code !== 0) {
        throw new Error(response.msg || '获取告警信息失败');
      }
      return response.data;
    },
    staleTime: 5000,
    refetchInterval: 10000,
  });
}

// ========== 私有代理相关 Hooks ==========

/**
 * 获取私有代理列表
 */
export function usePrivateProxies() {
  return useQuery({
    queryKey: queryKeys.privateProxy.list(),
    queryFn: async () => {
      const response = await api.getPrivateProxies();
      if (response.code !== 0) {
        throw new Error(response.msg || '获取私有代理列表失败');
      }
      return response.data;
    },
    staleTime: 8000,
  });
}

// ========== 通用Hook：乐观更新 ==========

/**
 * 通用的乐观更新Hook
 * 用于在mutation执行前立即更新UI，提升用户体验
 */
export function useOptimisticMutation<TData = any, TVariables = any>({
  mutationFn,
  queryKey,
  updateFn,
  successMessage,
  errorMessage,
}: {
  mutationFn: (variables: TVariables) => Promise<TData>;
  queryKey: readonly unknown[];
  updateFn: (oldData: any, variables: TVariables) => any;
  successMessage?: string;
  errorMessage?: string;
}) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onMutate: async (variables) => {
      // 取消正在进行的查询
      await queryClient.cancelQueries({ queryKey });

      // 获取当前数据快照
      const previousData = queryClient.getQueryData(queryKey);

      // 乐观更新
      queryClient.setQueryData(queryKey, (old: any) => updateFn(old, variables));

      // 返回上下文，用于回滚
      return { previousData };
    },
    onError: (error: Error, variables, context: any) => {
      // 回滚到之前的数据
      if (context?.previousData) {
        queryClient.setQueryData(queryKey, context.previousData);
      }
      toast.error(errorMessage || error.message);
    },
    onSuccess: () => {
      if (successMessage) {
        toast.success(successMessage);
      }
    },
    onSettled: () => {
      // 无论成功还是失败，都重新获取数据确保同步
      queryClient.invalidateQueries({ queryKey });
    },
  });
}
