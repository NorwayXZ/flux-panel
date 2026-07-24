import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Card, CardBody } from '@heroui/card';
import { Spinner } from '@heroui/spinner';
import toast from 'react-hot-toast';

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

  const tagsResponse = await fetch(`https://api.github.com/repos/${repository}/tags?per_page=20`, {
    headers,
    cache: 'no-store'
  });

  if (!tagsResponse.ok) {
    throw new Error(`GitHub Tags 请求失败（${tagsResponse.status}）`);
  }

  const tags = await tagsResponse.json() as Array<{ name?: string; commit?: { sha?: string } }>;
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

const formatCheckedTime = (value: Date | null): string => {
  if (!value) return '尚未检查';
  return value.toLocaleString('zh-CN', { hour12: false });
};

export default function UpdatePage() {
  const [state, setState] = useState<CheckState>('idle');
  const [remoteVersion, setRemoteVersion] = useState<RemoteVersion | null>(null);
  const [errorMessage, setErrorMessage] = useState('');
  const [lastChecked, setLastChecked] = useState<Date | null>(null);

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

  useEffect(() => {
    void checkForUpdates();
  }, [checkForUpdates]);

  const hasUpdate = useMemo(() => (
    remoteVersion ? compareVersions(remoteVersion.version, siteConfig.version) > 0 : false
  ), [remoteVersion]);

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

  return (
    <div className="min-h-full bg-gray-100 dark:bg-black px-4 py-5 lg:px-6 lg:py-6">
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
                <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M14 5h5v5m0-5l-8 8M19 14v4a1 1 0 01-1 1H6a1 1 0 01-1-1V6a1 1 0 011-1h4" />
                </svg>
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
                <a href={remoteVersion.url} target="_blank" rel="noopener noreferrer">
                  <Button
                    color={hasUpdate ? 'warning' : 'default'}
                    variant="flat"
                    endContent={<span aria-hidden>↗</span>}
                  >
                    查看版本
                  </Button>
                </a>
              </div>
            )}

            <p className="text-xs text-default-400">上次检查：{formatCheckedTime(lastChecked)}</p>
          </CardBody>
        </Card>
      </div>
    </div>
  );
}
