import type { ExitCountry, PrivateProxyExitType } from "@/api";

import { Spinner } from "@heroui/spinner";
import { Home, Server } from "lucide-react";

import { sortExitCountries } from "@/utils/exit-display";

export interface ExitChoice {
  exitType: PrivateProxyExitType;
  exitCountry: string;
}

interface ExitPickerProps {
  value: ExitChoice;
  countries: ExitCountry[];
  loading?: boolean;
  /** 候选列表读取失败时的说明 */
  error?: string;
  /** 不能使用家宽出口的原因，例如协议不支持或节点版本过低 */
  blockedReason?: string;
  onChange: (value: ExitChoice) => void;
}

const optionClass = (selected: boolean, disabled = false) =>
  [
    "flex w-full items-start gap-3 rounded-lg border px-4 py-3 text-left transition-colors",
    selected
      ? "border-primary bg-primary-50 dark:bg-primary-500/10"
      : "border-divider hover:bg-default-50",
    disabled ? "cursor-not-allowed opacity-50 hover:bg-transparent" : "",
  ].join(" ");

/** 选择代理的出口：节点自身 IP，或某个国家的家宽。 */
export default function ExitPicker({
  value,
  countries,
  loading,
  error,
  blockedReason,
  onChange,
}: ExitPickerProps) {
  const residential = value.exitType === "residential";
  const sorted = sortExitCountries(countries);

  return (
    <div className="space-y-3">
      <div className="text-sm font-medium">出口</div>
      <div className="grid gap-3 md:grid-cols-2">
        <button
          aria-pressed={!residential}
          className={optionClass(!residential)}
          type="button"
          onClick={() => onChange({ exitType: "node", exitCountry: "" })}
        >
          <Server className="mt-0.5 shrink-0 text-default-500" size={18} />
          <span>
            <span className="block text-sm font-medium">节点自身 IP</span>
            <span className="mt-0.5 block text-xs text-default-500">
              网站看到的是节点的 IP
            </span>
          </span>
        </button>
        <button
          aria-pressed={residential}
          className={optionClass(residential, Boolean(blockedReason))}
          disabled={Boolean(blockedReason)}
          type="button"
          onClick={() =>
            onChange({
              exitType: "residential",
              exitCountry: value.exitCountry,
            })
          }
        >
          <Home className="mt-0.5 shrink-0 text-default-500" size={18} />
          <span>
            <span className="block text-sm font-medium">指定国家的家宽</span>
            <span className="mt-0.5 block text-xs text-default-500">
              自动选择可用的出口，失效时自动更换
            </span>
          </span>
        </button>
      </div>
      {blockedReason && (
        <p className="text-xs text-default-500">{blockedReason}</p>
      )}
      {residential && !blockedReason && (
        <div className="space-y-3 rounded-lg border border-divider px-4 py-3">
          {loading ? (
            <div className="flex items-center gap-2 text-sm text-default-500">
              <Spinner size="sm" />
              正在读取各国可用的出口
            </div>
          ) : sorted.length === 0 ? (
            <p className="text-sm text-default-500">
              {error
                ? `读取出口列表失败：${error}`
                : "暂时没有读取到任何出口，请稍后再试。"}
            </p>
          ) : (
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
              {sorted.map((country) => {
                const selected = value.exitCountry === country.country;
                const unavailable = country.available <= 0;

                return (
                  <button
                    key={country.country}
                    aria-pressed={selected}
                    className={[
                      "rounded-md border px-3 py-2 text-left transition-colors",
                      selected
                        ? "border-primary bg-primary-50 dark:bg-primary-500/10"
                        : "border-divider hover:bg-default-50",
                      unavailable
                        ? "cursor-not-allowed opacity-50 hover:bg-transparent"
                        : "",
                    ].join(" ")}
                    disabled={unavailable}
                    type="button"
                    onClick={() =>
                      onChange({
                        exitType: "residential",
                        exitCountry: country.country,
                      })
                    }
                  >
                    <span className="block text-sm font-medium">
                      {country.name}
                    </span>
                    <span className="block text-xs text-default-500">
                      {unavailable ? "暂无" : `${country.available} 个可用`}
                    </span>
                  </button>
                );
              })}
            </div>
          )}
          <p className="text-xs leading-5 text-default-500">
            出口来自 VPN Gate
            志愿者的家庭宽带，由多人共用：速度不稳定，更换出口时会断开几秒；出口不可用时代理会断开，不会改用节点自身
            IP。请不要通过它传输未加密的敏感内容。首次使用时节点会自动安装
            OpenVPN。
          </p>
        </div>
      )}
    </div>
  );
}
