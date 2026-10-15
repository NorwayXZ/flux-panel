import type { PrivateProxyClientConfig } from "@/api";

import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Textarea } from "@heroui/input";
import { Copy, Download, QrCode } from "lucide-react";
import toast from "react-hot-toast";

import ProxyQr from "@/components/proxy-qr";
import { getPrivateProxySubscription } from "@/api";

interface ProxyImportProps {
  proxyId: number;
  config: PrivateProxyClientConfig;
}

const subscriptionUrl = (token: string, format: string, proxyId: number) =>
  `${window.location.origin}/api/v1/open_api/proxy-sub?token=${token}&format=${format}&id=${proxyId}`;

/** 把单个代理导入 Clash 或 sing-box：一键打开客户端，或复制订阅链接、扫码。 */
export default function ProxyImport({ proxyId, config }: ProxyImportProps) {
  const [token, setToken] = useState("");
  const [qr, setQr] = useState("");
  const [showYaml, setShowYaml] = useState(false);

  useEffect(() => {
    let alive = true;

    getPrivateProxySubscription().then((response) => {
      if (alive && response.code === 0 && response.data)
        setToken(response.data.token);
    });

    return () => {
      alive = false;
    };
  }, [proxyId]);

  const clash = Boolean(config.clash);
  const singBox = config.proxyType !== "vless_xhttp";

  if (!token || (!clash && !singBox)) return null;
  const clashUrl = subscriptionUrl(token, "clash", proxyId);
  const singBoxUrl = subscriptionUrl(token, "singbox", proxyId);
  const name = encodeURIComponent(config.name);

  const copy = async (value: string, label: string) => {
    await navigator.clipboard.writeText(value);
    toast.success(`${label}已复制`);
  };

  const rows = [
    clash && {
      key: "clash",
      label: "Clash / mihomo",
      hint: "Clash Verge、FlClash、Clash Meta for Android、Stash 等",
      url: clashUrl,
      open: `clash://install-config?url=${encodeURIComponent(clashUrl)}&name=${name}`,
    },
    singBox && {
      key: "singbox",
      label: "sing-box",
      hint: "sing-box 官方客户端（SFA / SFI / SFM）",
      url: singBoxUrl,
      open: `sing-box://import-remote-profile?url=${encodeURIComponent(singBoxUrl)}#${name}`,
    },
  ].filter(Boolean) as {
    key: string;
    label: string;
    hint: string;
    url: string;
    open: string;
  }[];

  return (
    <div className="space-y-3 border-t border-divider pt-4">
      <div>
        <div className="text-sm font-medium">导入到客户端</div>
        <p className="mt-1 text-xs leading-5 text-default-500">
          电脑或手机上装好客户端后，点“一键导入”会自动打开客户端并添加这个代理；
          没有反应就复制订阅链接，在客户端的“订阅 / 配置 /
          Profiles”里粘贴添加；手机也可以扫码。Shadowrocket
          请扫上面导入链接的二维码。
        </p>
      </div>
      {rows.map((row) => (
        <div
          key={row.key}
          className="space-y-2 rounded-md border border-divider p-3"
        >
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium">{row.label}</span>
            <span className="text-xs text-default-500">{row.hint}</span>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              as="a"
              color="primary"
              href={row.open}
              size="sm"
              startContent={<Download size={15} />}
            >
              一键导入
            </Button>
            <Button
              size="sm"
              startContent={<Copy size={15} />}
              variant="flat"
              onPress={() => copy(row.url, `${row.label} 订阅链接`)}
            >
              复制订阅链接
            </Button>
            <Button
              size="sm"
              startContent={<QrCode size={15} />}
              variant={qr === row.key ? "solid" : "light"}
              onPress={() => setQr(qr === row.key ? "" : row.key)}
            >
              二维码
            </Button>
          </div>
          {qr === row.key && (
            <ProxyQr label={`${row.label} 订阅二维码`} value={row.url} />
          )}
        </div>
      ))}
      {clash && (
        <div>
          <button
            className="text-xs text-default-500 underline-offset-2 hover:underline"
            type="button"
            onClick={() => setShowYaml(!showYaml)}
          >
            {showYaml ? "收起" : "查看"} Clash 配置内容（手动编辑配置文件时用）
          </button>
          {showYaml && (
            <div className="mt-2 space-y-2">
              <Textarea
                isReadOnly
                classNames={{ input: "font-mono text-xs" }}
                maxRows={12}
                minRows={4}
                value={config.clash}
              />
              <Button
                size="sm"
                startContent={<Copy size={15} />}
                variant="flat"
                onPress={() => copy(config.clash || "", "Clash 配置")}
              >
                复制配置内容
              </Button>
            </div>
          )}
        </div>
      )}
      <p className="text-xs text-default-500">
        订阅链接相当于密码，不要发给别人；在页面顶部“订阅链接”里重置后，这些链接也会失效。
      </p>
    </div>
  );
}
