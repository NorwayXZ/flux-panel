import { useEffect, useState } from "react";
import QRCode from "qrcode";

interface ProxyQrProps {
  value: string;
  size?: number;
  label?: string;
}

/** 把链接画成二维码，手机客户端扫码导入。 */
export default function ProxyQr({ value, size = 208, label }: ProxyQrProps) {
  const [image, setImage] = useState("");
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;

    setFailed(false);
    QRCode.toDataURL(value, {
      errorCorrectionLevel: "M",
      margin: 1,
      width: size,
    })
      .then((url) => alive && setImage(url))
      .catch(() => alive && setFailed(true));

    return () => {
      alive = false;
    };
  }, [value, size]);

  if (failed)
    return (
      <p className="text-xs text-default-500">内容太长，无法生成二维码。</p>
    );
  if (!image) return <div style={{ width: size, height: size }} />;

  return (
    <img
      alt={label || "二维码"}
      className="rounded-md border border-divider bg-white p-1"
      height={size}
      src={image}
      width={size}
    />
  );
}
