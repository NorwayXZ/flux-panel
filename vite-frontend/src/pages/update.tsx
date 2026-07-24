import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Card, CardBody } from '@heroui/card';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Spinner } from '@heroui/spinner';
import toast from 'react-hot-toast';

import {
  getSystemUpdateStatus,
  triggerSystemUpdate,
  type SystemUpdateState,
  type SystemUpdateStatus
} from '@/api';
import { siteConfig } from '@/config/site';

type CheckState = 'idle' | 'checking' | 'success' | 'error';

interface RemoteVersion {
  version: string;
  title: string;
  url: string;
  publishedAt?: string;
  source: 'release' | 'tag';
}

const normalizeVersion = (value: string): string => value.trim().replace(/^v/i, '');

const versionParts = (value: string): number[] => {
  const match = normalizeVersion(value).match(/\d+(?:\.\d+)*/);
  return match ? match[0].split('.').map(Number) : [];
};

const compareVersions = (left: string, right: string): number => {
  const leftParts = versionParts(left);
  const rightParts = versionParts(right);
  const length = Math.max(leftParts.length, rightParts.length);

  for (let index = 0; index < length; index += 1) {
    const difference = (leftParts[index] || 0) - (rightParts[index] || 0);
    if (difference !== 0) return difference;
  }

  return 0;
};

const getLatestVersion = async (): Promise<RemoteVersion | null> => {
  const repository = siteConfig.updateRepository;
  const headers = { Accept: 'application/vnd.github+json' };
  const releaseResponse = await fetch(`https://api.github.com/repos/${repository}/releases/latest`, {
    headers,
    cache: 'no-store'
  });

  if (releaseResponse.ok) {
    const release = await releaseResponse.json() as {
      tag_name?: string;
      name?: string;
      html_url?: string;
      published_at?: string;
    };

    if (release.tag_name) {
      return {
        version: normalizeVersion(release.tag_name),
        title: release.name || release.tag_name,
        url: release.html_url || `https://github.com/${repository}/releases`,
        publishedAt: release.published_at,
        source: 'release'
      };
    }
  } else if (releaseResponse.status !== 404) {
    throw new Error(`GitHub Releases 请求失败（${releaseResponse.status}）`);
  }

  const tagsResponse = await fetch(`https://api.github.com/repos/${repository}/tags?per_page=30`, {
    headers,
    cache: 'no-store'
  });

  if (!tagsResponse.ok) {
    throw new Error(`GitHub Tags 请求失败（${tagsResponse.status}）`);
  }

  const tags = await tagsResponse.json() as Array<{ name?: string }>;
  const versionTag = tags
    .filter(tag => Boolean(tag.name && versionParts(tag.name).length > 0))
    .sort((left, right) => compareVersions(right.name || '', left.name || ''))[0];

  if (!versionTag?.name) return null;

  return {
    version: normalizeVersion(versionTag.name),
    title: versionTag.name,
    url: `https://github.com/${repository}/tree/${encodeURIComponent(versionTag.name)}`,
    source: 'tag'
  };
};

const formatTime = (value: Date | number | null): string => {
  if (!value) return '尚未检查';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
};

const updateStateLabels: Record<SystemUpdateState, string> = {
  idle: '等待更新',
  queued: '已提交',
  running: '正在更新',
  success: '更新完成',
  failed: '更新失败',
  unknown: '状态未知'
};

const updateStateClasses: Record<SystemUpdateState, string> = {
  idle: 'text-default-500',
  queued: 'text-primary-500',
  running: 'text-primary-500',
  success: 'text-success',
  failed: 'text-danger',
  unknown: 'text-warning'
};

export default function UpdatePage() {
  const [state, setState] = useState<CheckState>('idle');
  const [remoteVersion, setRemoteVersion] = useState<RemoteVersion | null>(null);
  const [errorMessage, setErrorMessage] = useState('');
  const [lastChecked, setLastChecked] = useState<Date | null>(null);
  const [systemStatus, setSystemStatus] = useState<SystemUpdateStatus | null>(null);
  const [statusUnavailable, setStatusUnavailable] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const checkForUpdates = useCallback(async () => {
    setState('checking');
    setErrorMessage('');

    try {
      const version = await getLatestVersion();
      setRemoteVersion(version);
      setLastChecked(new Date());
      setState('success');
    } catch (error) {
      const message = error instanceof Error ? error.message : '检查更新失败';
      setErrorMessage(message);
      setState('error');
      setLastChecked(new Date());
      toast.error(message);
    }
  }, []);

  const refreshSystemStatus = useCallback(async () => {
    const response = await getSystemUpdateStatus();
    if (response.code === 0 && response.data) {
      setSystemStatus(response.data);
      setStatusUnavailable(false);
      return;
    }
    setStatusUnavailable(true);
  }, []);

  useEffect(() => {
    void checkForUpdates();
  }, [checkForUpdates]);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      await refreshSystemStatus();
      if (!stopped) {
        const active = systemStatus?.state === 'queued' || systemStatus?.state === 'running';
        timer = setTimeout(poll, active ? 2500 : 10000);
      }
    };

    void poll();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
    };
  }, [refreshSystemStatus, systemStatus?.state]);

  const hasUpdate = useMemo(() => (
    remoteVersion ? compareVersions(remoteVersion.version, siteConfig.version) > 0 : false
  ), [remoteVersion]);

  const updateActive = systemStatus?.state === 'queued' || systemStatus?.state === 'running';
  const statusText = state === 'checking'
    ? '正在检查'
    : state === 'error'
      ? '检查失败'
      : hasUpdate
        ? '发现新版本'
        : remoteVersion
          ? '当前已是最新版本'
          : '暂无正式版本';

  const statusClass = state === 'error'
    ? 'text-danger'
    : hasUpdate
      ? 'text-warning'
      : 'text-success';

  const submitUpdate = async () => {
    setSubmitting(true);
    const response = await triggerSystemUpdate();
    setSubmitting(false);

    if (response.code !== 0 || !response.data) {
      toast.error(response.msg || '更新任务提交失败');
      return;
    }

    setSystemStatus(response.data);
    setConfirmOpen(false);
    toast.success('更新任务已提交');
  };

  return (
    <div className="min-h-full bg-gray-100 px-4 py-5 dark:bg-black lg:px-6 lg:py-6">
      <div className="mx-auto max-w-4xl space-y-5">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-sm text-default-500">版本维护</p>
            <h1 className="text-2xl font-semibold text-foreground">检查更新</h1>
          </div>
          <Button
            color="primary"
            variant="flat"
            startContent={state === 'checking' ? <Spinner size="sm" color="current" /> : (
              <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M20 11a8.1 8.1 0 00-14.8-4.3L3 9m0 0V4m0 5h5M4 13a8.1 8.1 0 0014.8 4.3L21 15m0 0v5m0-5h-5" />
              </svg>
            )}
            onPress={() => void checkForUpdates()}
            isDisabled={state === 'checking'}
          >
            重新检查
          </Button>
        </div>

        <Card className="border border-default-200 shadow-sm">
          <CardBody className="grid gap-5 p-5 sm:grid-cols-3">
            <div>
              <p className="text-xs text-default-500">当前版本</p>
              <p className="mt-1 text-2xl font-semibold text-foreground">v{siteConfig.version}</p>
            </div>
            <div>
              <p className="text-xs text-default-500">远端版本</p>
              <p className="mt-1 text-2xl font-semibold text-foreground">
                {state === 'checking' ? '检查中...' : remoteVersion ? `v${remoteVersion.version}` : '--'}
              </p>
            </div>
            <div>
              <p className="text-xs text-default-500">状态</p>
              <p className={`mt-1 text-lg font-semibold ${statusClass}`}>{statusText}</p>
            </div>
          </CardBody>
        </Card>

        <Card className="border border-default-200 shadow-sm">
          <CardBody className="space-y-4 p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold text-foreground">更新来源</h2>
                <p className="mt-1 text-sm text-default-500">{siteConfig.updateRepository}</p>
              </div>
              <a
                href={`https://github.com/${siteConfig.updateRepository}`}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-sm text-primary-600 hover:text-primary-500"
              >
                打开项目
                <span aria-hidden>↗</span>
              </a>
            </div>

            {state === 'error' && (
              <div className="rounded-lg border border-danger-200 bg-danger-50 px-4 py-3 text-sm text-danger-700 dark:border-danger-500/30 dark:bg-danger-500/10 dark:text-danger-300">
                {errorMessage}
              </div>
            )}

            {state === 'success' && !remoteVersion && (
              <div className="rounded-lg border border-default-200 bg-default-50 px-4 py-3 text-sm text-default-600 dark:border-default-700 dark:bg-default-900/30 dark:text-default-300">
                GitHub 当前没有可识别的 Release 或版本 Tag。
              </div>
            )}

            {state === 'success' && remoteVersion && (
              <div className={`flex flex-wrap items-center justify-between gap-4 rounded-lg border px-4 py-3 ${hasUpdate ? 'border-warning-200 bg-warning-50 dark:border-warning-500/30 dark:bg-warning-500/10' : 'border-success-200 bg-success-50 dark:border-success-500/30 dark:bg-success-500/10'}`}>
                <div>
                  <p className="font-medium text-foreground">{remoteVersion.title}</p>
                  <p className="mt-1 text-xs text-default-500">
                    {remoteVersion.source === 'release' ? '正式版本' : '版本 Tag'}
                    {remoteVersion.publishedAt ? ` · ${new Date(remoteVersion.publishedAt).toLocaleDateString('zh-CN')}` : ''}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <a href={remoteVersion.url} target="_blank" rel="noopener noreferrer">
                    <Button variant="flat">查看版本</Button>
                  </a>
                  {hasUpdate && (
                    <Button
                      color="warning"
                      onPress={() => setConfirmOpen(true)}
                      isDisabled={!systemStatus?.supported || updateActive}
                    >
                      {updateActive ? '正在更新' : '立即更新'}
                    </Button>
                  )}
                </div>
              </div>
            )}

            <p className="text-xs text-default-400">上次检查：{formatTime(lastChecked)}</p>
          </CardBody>
        </Card>

        {(systemStatus || statusUnavailable) && (
          <Card className="border border-default-200 shadow-sm">
            <CardBody className="space-y-4 p-5">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h2 className="text-base font-semibold text-foreground">在线更新服务</h2>
                  <p className="mt-1 text-sm text-default-500">
                    {statusUnavailable
                      ? '面板重启中或暂时无法读取状态'
                      : systemStatus?.supported
                        ? '宿主机更新服务已连接'
                        : '当前安装方式尚未启用宿主机更新服务'}
                  </p>
                </div>
                {systemStatus && (
                  <p className={`text-sm font-semibold ${updateStateClasses[systemStatus.state]}`}>
                    {updateStateLabels[systemStatus.state]}
                  </p>
                )}
              </div>

              {systemStatus?.startedAt ? (
                <div className="grid gap-3 text-sm sm:grid-cols-2">
                  <p className="text-default-500">开始时间：<span className="text-foreground">{formatTime(systemStatus.startedAt)}</span></p>
                  <p className="text-default-500">完成时间：<span className="text-foreground">{systemStatus.finishedAt ? formatTime(systemStatus.finishedAt) : '--'}</span></p>
                </div>
              ) : null}

              {systemStatus?.logs.length ? (
                <pre className="max-h-64 overflow-auto rounded-md border border-default-200 bg-default-100 p-3 text-xs leading-5 text-default-700 dark:border-default-700 dark:bg-default-900 dark:text-default-300">
                  {systemStatus.logs.join('\n')}
                </pre>
              ) : null}

              {systemStatus?.state === 'success' && (
                <div className="flex justify-end">
                  <Button color="success" variant="flat" onPress={() => window.location.reload()}>
                    刷新面板
                  </Button>
                </div>
              )}

              {systemStatus && !systemStatus.supported && (
                <div className="rounded-md border border-default-200 bg-default-50 px-4 py-3 text-sm dark:border-default-700 dark:bg-default-900/40">
                  <p className="text-default-600 dark:text-default-300">可先在服务器执行一次命令行更新以安装在线更新服务：</p>
                  <code className="mt-2 block overflow-x-auto text-xs text-foreground">curl -fsSL https://raw.githubusercontent.com/NorwayXZ/flux-panel/main/scripts/flux-panel.sh | sudo bash -s -- update</code>
                </div>
              )}
            </CardBody>
          </Card>
        )}
      </div>

      <Modal isOpen={confirmOpen} onOpenChange={setConfirmOpen} placement="center" backdrop="blur">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>确认在线更新</ModalHeader>
              <ModalBody>
                <p className="text-sm text-default-600 dark:text-default-300">
                  将从固定的 main 分支下载源码、重新构建前后端并执行健康检查。构建完成后服务会短暂重启；构建失败会恢复上一份源码，数据库卷不会删除。
                </p>
              </ModalBody>
              <ModalFooter>
                <Button variant="flat" onPress={onClose} isDisabled={submitting}>取消</Button>
                <Button color="warning" onPress={() => void submitUpdate()} isLoading={submitting}>
                  开始更新
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}
