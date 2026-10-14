import type { PrivateProxyItem, PrivateProxyUpdateRequest } from "@/api";

import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Checkbox } from "@heroui/checkbox";
import { Input, Textarea } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Select, SelectItem } from "@heroui/select";
import toast from "react-hot-toast";

import { updatePrivateProxy } from "@/api";
import CamouflagePicker from "@/components/camouflage-picker";

interface ProxyEditModalProps {
  target: PrivateProxyItem | null;
  /** 管理员，或代理是用户自己创建的 */
  canManage: boolean;
  onClose: () => void;
  onSaved: () => void;
}

type Renewal = "" | "24" | "168" | "720" | "8760" | "permanent";

/** 修改代理：名称、连接地址、端口、白名单、续期、重置密钥。 */
export default function ProxyEditModal({
  target,
  canManage,
  onClose,
  onSaved,
}: ProxyEditModalProps) {
  const [name, setName] = useState("");
  const [connectHost, setConnectHost] = useState("");
  const [port, setPort] = useState("");
  const [cidrs, setCidrs] = useState("");
  const [renewal, setRenewal] = useState<Renewal>("");
  const [reset, setReset] = useState(false);
  const [camouflage, setCamouflage] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!target) return;
    setName(target.name);
    setConnectHost(target.connectHost || "");
    setPort(String(target.listenPort));
    setCidrs(target.allowedCidrs || "");
    setRenewal("");
    setReset(false);
    setCamouflage(target.camouflage || "");
  }, [target]);

  if (!target) return null;
  const active = target.state === "active";
  const canRenew =
    canManage &&
    !target.granted &&
    (target.state === "active" || target.state === "paused");
  const supportsCidrs = target.proxyType !== "wireguard";
  const portChanged = port.trim() !== String(target.listenPort);
  const cidrsChanged = cidrs.trim() !== (target.allowedCidrs || "");
  const hasCamouflage =
    target.proxyType === "vless_reality" ||
    target.proxyType === "vless_xhttp" ||
    target.proxyType === "shadowtls";
  const camouflageChanged =
    hasCamouflage && camouflage.trim() !== (target.camouflage || "");
  const rebuilds = reset || portChanged || cidrsChanged;

  const save = async () => {
    const request: PrivateProxyUpdateRequest = { id: target.id };

    if (name.trim() !== target.name) request.name = name.trim();
    if (connectHost.trim() !== (target.connectHost || ""))
      request.connectHost = connectHost.trim();
    if (portChanged) {
      const value = Number(port);

      if (!Number.isInteger(value) || value < 1 || value > 65535)
        return toast.error("监听端口应为 1-65535");
      request.listenPort = value;
    }
    if (cidrsChanged) request.allowedCidrs = cidrs.trim();
    if (renewal === "permanent") request.permanent = true;
    else if (renewal) request.renewHours = Number(renewal);
    if (reset) request.resetCredentials = true;
    if (camouflageChanged) {
      if (!camouflage.trim()) return toast.error("请填写伪装站域名");
      if (target.proxyType === "shadowtls")
        request.handshakeServer = camouflage.trim();
      else request.realityServerName = camouflage.trim();
    }
    if (Object.keys(request).length === 1) return onClose();
    if (
      rebuilds &&
      !window.confirm(
        reset
          ? "重置后旧的密码或密钥立即失效，所有设备都需要重新导入连接信息。确认继续吗？"
          : "保存后代理会在节点上重建，正在进行的连接会断开，部分协议的连接信息会变化。确认继续吗？",
      )
    )
      return;
    setSaving(true);
    const response = await updatePrivateProxy(request);

    setSaving(false);
    if (response.code !== 0) return toast.error(response.msg || "保存失败");
    toast.success(
      rebuilds || camouflageChanged
        ? "已保存，请重新导入连接信息或更新订阅"
        : "已保存",
    );
    onSaved();
  };

  return (
    <Modal isOpen scrollBehavior="inside" size="2xl" onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>修改“{target.name}”</ModalHeader>
        <ModalBody className="gap-4">
          <div className="grid gap-4 md:grid-cols-2">
            <Input label="代理名称" value={name} onValueChange={setName} />
            <Input
              description="域名指向节点、或节点有多个地址时填写；留空使用节点地址"
              label="连接地址（可选）"
              placeholder={target.publicHost || "节点地址"}
              value={connectHost}
              onValueChange={setConnectHost}
            />
            {canManage && (
              <Input
                isDisabled={!active}
                label="监听端口"
                type="number"
                value={port}
                onValueChange={setPort}
              />
            )}
            {canRenew && (
              <Select
                description={
                  target.expiresAt
                    ? `当前到期：${new Date(target.expiresAt).toLocaleString()}`
                    : "当前永久有效"
                }
                label="续期"
                selectedKeys={renewal ? [renewal] : []}
                onSelectionChange={(keys) =>
                  setRenewal(String(Array.from(keys)[0] || "") as Renewal)
                }
              >
                <SelectItem key="24">延长 1 天</SelectItem>
                <SelectItem key="168">延长 7 天</SelectItem>
                <SelectItem key="720">延长 30 天</SelectItem>
                <SelectItem key="8760">延长 365 天</SelectItem>
                <SelectItem key="permanent">改为永久有效</SelectItem>
              </Select>
            )}
          </div>
          {canManage && supportsCidrs && (
            <Textarea
              description="只允许这些地址连接；留空允许任意来源"
              isDisabled={!active}
              label="来源 IP 白名单"
              minRows={2}
              placeholder="203.0.113.10/32, 2001:db8::/64"
              value={cidrs}
              onValueChange={setCidrs}
            />
          )}
          {canManage && hasCamouflage && active && (
            <CamouflagePicker
              label={
                target.proxyType === "shadowtls" ? "握手网站" : "REALITY 伪装站"
              }
              nodeId={target.nodeId}
              value={camouflage}
              onChange={setCamouflage}
            />
          )}
          {camouflageChanged && target.proxyType !== "shadowtls" && (
            <p className="text-xs text-default-500">
              更换伪装站会保留原来的 UUID 和密钥，只是客户端里的 SNI
              要改成新网站；用订阅的更新一下即可。
            </p>
          )}
          <Checkbox
            isDisabled={!active}
            isSelected={reset}
            onValueChange={setReset}
          >
            重新生成密码或密钥（怀疑泄露时使用，旧的立即失效）
          </Checkbox>
          {rebuilds && (
            <p className="rounded-md bg-warning-50 px-3 py-2 text-xs leading-5 text-warning-700 dark:bg-warning-500/10 dark:text-warning-400">
              修改端口、白名单或重置密钥会在节点上重建这个代理，正在进行的连接会断开。
              {target.proxyType !== "socks5" &&
              target.proxyType !== "http" &&
              target.proxyType !== "mixed" &&
              target.proxyType !== "shadowsocks"
                ? "证书或密钥会重新生成，设备需要重新导入，用订阅的会自动更新。"
                : ""}
              修改失败时会自动恢复原来的设置。
            </p>
          )}
          {!active && (
            <p className="text-xs text-default-500">
              代理没有在运行，只能修改名称、连接地址和有效期。
            </p>
          )}
        </ModalBody>
        <ModalFooter>
          <Button variant="flat" onPress={onClose}>
            取消
          </Button>
          <Button color="primary" isLoading={saving} onPress={save}>
            保存
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
