import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import { Copy, QrCode, RefreshCw } from "lucide-react";
import toast from "react-hot-toast";

import ProxyQr from "@/components/proxy-qr";
import { getPrivateProxySubscription } from "@/api";

interface ProxySubscriptionModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const FORMATS = [
  {
    key: "base64",
    label: "通用订阅",
    hint: "Shadowrocket、v2rayN、NekoBox 等；客户端会自动识别，Clash 和 sing-box 也能直接用这一条",
    suffix: "",
  },
  {
    key: "clash",
    label: "Clash / mihomo",
    hint: "Clash Verge、FlClash、Stash 等，证书已固定",
    suffix: "&format=clash",
  },
  {
    key: "singbox",
    label: "sing-box",
    hint: "sing-box 官方客户端（SFA / SFI / SFM），证书已固定",
    suffix: "&format=singbox",
  },
];

/** 订阅链接：客户端定时拉取，自动得到全部运行中的代理。 */
export default function ProxySubscriptionModal({
  isOpen,
  onClose,
}: ProxySubscriptionModalProps) {
  const [token, setToken] = useState("");
  const [loading, setLoading] = useState(false);
  const [qr, setQr] = useState("");

  const load = async (reset = false) => {
    setLoading(true);
    const response = await getPrivateProxySubscription(reset);

    setLoading(false);
    if (response.code !== 0 || !response.data)
      return toast.error(response.msg || "读取订阅失败");
    setToken(response.data.token);
    if (reset) toast.success("订阅链接已重置，旧链接已失效");
  };

  useEffect(() => {
    if (isOpen) {
      setQr("");
      void load();
    }
  }, [isOpen]);

  const base = `${window.location.origin}/api/v1/open_api/proxy-sub?token=${token}`;

  return (
    <Modal
      isOpen={isOpen}
      scrollBehavior="inside"
      size="2xl"
      onOpenChange={onClose}
    >
      <ModalContent>
        <ModalHeader>订阅链接</ModalHeader>
        <ModalBody className="gap-4">
          <p className="text-sm leading-6 text-default-600">
            在客户端里添加订阅，就能得到你全部运行中的代理；新建、修改或重置密钥后，客户端更新订阅即可，不用逐个导入。
          </p>
          {loading && !token ? (
            <div className="flex justify-center py-6">
              <Spinner />
            </div>
          ) : (
            <div className="divide-y divide-divider border-y border-divider">
              {FORMATS.map((format) => {
                const url = base + format.suffix;

                return (
                  <div key={format.key} className="space-y-2 py-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-medium">
                        {format.label}
                      </span>
                      <span className="text-xs text-default-500">
                        {format.hint}
                      </span>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <code className="min-w-0 flex-1 break-all rounded-md bg-default-100 px-3 py-2 text-xs">
                        {url}
                      </code>
                      <Button
                        isIconOnly
                        aria-label={`复制${format.label}`}
                        size="sm"
                        variant="flat"
                        onPress={async () => {
                          await navigator.clipboard.writeText(url);
                          toast.success(`${format.label}链接已复制`);
                        }}
                      >
                        <Copy size={15} />
                      </Button>
                      <Button
                        isIconOnly
                        aria-label={`显示${format.label}二维码`}
                        size="sm"
                        variant={qr === format.key ? "solid" : "light"}
                        onPress={() =>
                          setQr(qr === format.key ? "" : format.key)
                        }
                      >
                        <QrCode size={15} />
                      </Button>
                    </div>
                    {qr === format.key && (
                      <ProxyQr label={`${format.label}二维码`} value={url} />
                    )}
                  </div>
                );
              })}
            </div>
          )}
          <p className="text-xs leading-5 text-default-500">
            拿到订阅链接的人可以使用你的全部代理，请当作密码保管。怀疑泄露时点“重置链接”，旧链接立即失效。
            ShadowTLS 没有通用链接格式，只出现在 Clash 和 sing-box 订阅里；VLESS
            + XHTTP 只出现在通用订阅里。
          </p>
        </ModalBody>
        <ModalFooter>
          <Button
            isDisabled={!token}
            startContent={<RefreshCw size={15} />}
            variant="light"
            onPress={() => {
              if (window.confirm("重置后旧的订阅链接立即失效，确认重置吗？"))
                void load(true);
            }}
          >
            重置链接
          </Button>
          <Button color="primary" onPress={onClose}>
            完成
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
