// Shared display formatting. Pages keep their own wording for empty values by
// passing a fallback instead of redefining these helpers.

export const formatDateTime = (
  value?: Date | number | null,
  fallback = "-",
): string =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : fallback;

// The API returns flags as either booleans or 0/1.
export const truthy = (value?: boolean | number | null): boolean =>
  value === true || value === 1;

const BYTE_UNITS = ["B", "KB", "MB", "GB", "TB"];

const byteScale = (value: number) => {
  const index = Math.min(
    Math.floor(Math.log(value) / Math.log(1024)),
    BYTE_UNITS.length - 1,
  );

  return { index, amount: value / 1024 ** index };
};

// Two decimals below 100 of a unit, none above: "1.50 GB", "512 MB", "42 B".
export const formatBytes = (value = 0): string => {
  if (!Number.isFinite(value) || value <= 0) return "0 B";
  const { index, amount } = byteScale(value);

  return `${amount >= 100 || index === 0 ? amount.toFixed(0) : amount.toFixed(2)} ${BYTE_UNITS[index]}`;
};

// One decimal for every unit above bytes: "1.5 GB", "512.0 MB", "42 B".
export const formatBytesCompact = (value = 0): string => {
  if (!Number.isFinite(value) || value <= 0) return "0 B";
  const { index, amount } = byteScale(value);

  return `${amount.toFixed(index === 0 ? 0 : 1)} ${BYTE_UNITS[index]}`;
};
