import { Card, CardBody, CardHeader } from "@heroui/card";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

// Layout and chart styling follow NodePassDash's dashboard (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

export interface FlowBucket {
  flow: number;
  inFlow?: number;
  outFlow?: number;
  time: string;
  createdTime?: number;
}

export const formatBytes = (value: number): string => {
  if (!value) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = value;
  let unit = 0;

  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }

  return `${unit === 0 ? size : size.toFixed(2)} ${units[unit]}`;
};

const axisBytes = (value: number) => {
  if (!value) return "0";
  const units = ["B", "K", "M", "G", "T"];
  let size = value;
  let unit = 0;

  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }

  return `${Number.isInteger(size) ? size : size.toFixed(1)}${units[unit]}`;
};

const SERIES = {
  inFlow: { label: "入站", color: "#3b82f6" },
  outFlow: { label: "出站", color: "#10b981" },
  // Samples recorded before the panel stored directions only have a total.
  unsplit: { label: "未区分方向", color: "#a1a1aa" },
} as const;

type SeriesKey = keyof typeof SERIES;

/** Splits each bucket into inbound, outbound and any traffic without a direction. */
const splitBucket = (bucket: FlowBucket) => {
  const inFlow = bucket.inFlow || 0;
  const outFlow = bucket.outFlow || 0;

  return {
    inFlow,
    outFlow,
    unsplit: Math.max(0, (bucket.flow || 0) - inFlow - outFlow),
  };
};

const hourLabel = (bucket: FlowBucket) =>
  bucket.createdTime
    ? `${new Date(bucket.createdTime).getHours().toString().padStart(2, "0")}:00`
    : bucket.time;

const dayLabel = (bucket: FlowBucket) => {
  if (!bucket.createdTime) return bucket.time;
  const date = new Date(bucket.createdTime);

  return `${date.getMonth() + 1}/${date.getDate()}`;
};

const totals = (buckets: FlowBucket[]) =>
  buckets.reduce(
    (sum, bucket) => {
      const split = splitBucket(bucket);

      return {
        inFlow: sum.inFlow + split.inFlow,
        outFlow: sum.outFlow + split.outFlow,
        unsplit: sum.unsplit + split.unsplit,
        total: sum.total + (bucket.flow || 0),
      };
    },
    { inFlow: 0, outFlow: 0, unsplit: 0, total: 0 },
  );

function ChartTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: Array<{ dataKey?: string | number; value?: number }>;
  label?: string;
}) {
  if (!active || !payload?.length) return null;
  const visible = payload.filter((item) => item.value);

  return (
    <div className="rounded-lg border border-default-200 bg-content1 px-3 py-2 text-xs shadow-lg">
      <p className="mb-1 font-medium text-foreground">{label}</p>
      {(visible.length ? visible : payload).map((item) => {
        const series = SERIES[item.dataKey as SeriesKey];

        return (
          <div
            key={String(item.dataKey)}
            className="flex items-center justify-between gap-4"
          >
            <span className="flex items-center gap-1.5 text-default-500">
              <span
                className="h-2 w-2 rounded-full"
                style={{ background: series?.color }}
              />
              {series?.label}
            </span>
            <span className="font-medium text-foreground">
              {formatBytes(item.value || 0)}
            </span>
          </div>
        );
      })}
    </div>
  );
}

function Legend({ keys }: { keys: SeriesKey[] }) {
  return (
    <div className="flex flex-wrap items-center gap-3 text-xs text-default-500">
      {keys.map((key) => (
        <span key={key} className="flex items-center gap-1.5">
          <span
            className="h-2 w-2 rounded-full"
            style={{ background: SERIES[key].color }}
          />
          {SERIES[key].label}
        </span>
      ))}
    </div>
  );
}

const cardClass = "h-full border border-transparent dark:border-default-100";

/** 24-hour traffic as stacked gradient areas, inbound and outbound. */
export function TrafficOverviewChart({ hourly }: { hourly: FlowBucket[] }) {
  // Buckets arrive newest first.
  const data = [...hourly].reverse().map((bucket) => ({
    time: hourLabel(bucket),
    ...splitBucket(bucket),
  }));
  const sum = totals(hourly);
  const keys: SeriesKey[] =
    sum.unsplit > 0 ? ["inFlow", "outFlow", "unsplit"] : ["inFlow", "outFlow"];

  return (
    <Card className={cardClass}>
      <CardHeader className="flex flex-wrap items-start justify-between gap-3 p-5 pb-0">
        <div>
          <h2 className="text-base font-semibold text-foreground">流量趋势</h2>
          <p className="mt-0.5 text-sm text-default-500">最近 24 小时</p>
        </div>
        <div className="flex gap-5 text-right">
          <div>
            <p className="text-xs text-default-500">入站</p>
            <p className="text-sm font-semibold text-primary">
              {formatBytes(sum.inFlow)}
            </p>
          </div>
          <div>
            <p className="text-xs text-default-500">出站</p>
            <p className="text-sm font-semibold text-success">
              {formatBytes(sum.outFlow)}
            </p>
          </div>
          <div>
            <p className="text-xs text-default-500">合计</p>
            <p className="text-sm font-semibold text-foreground">
              {formatBytes(sum.total)}
            </p>
          </div>
        </div>
      </CardHeader>
      <CardBody className="gap-3 p-5 pt-4">
        <div className="h-72 w-full">
          <ResponsiveContainer height="100%" width="100%">
            <AreaChart
              data={data}
              margin={{ top: 5, right: 5, left: -4, bottom: 0 }}
            >
              <defs>
                {keys.map((key) => (
                  <linearGradient
                    key={key}
                    id={`traffic-${key}`}
                    x1="0"
                    x2="0"
                    y1="0"
                    y2="1"
                  >
                    <stop
                      offset="5%"
                      stopColor={SERIES[key].color}
                      stopOpacity={0.35}
                    />
                    <stop
                      offset="95%"
                      stopColor={SERIES[key].color}
                      stopOpacity={0.02}
                    />
                  </linearGradient>
                ))}
              </defs>
              <CartesianGrid
                stroke="currentColor"
                strokeOpacity={0.08}
                vertical={false}
              />
              <XAxis
                axisLine={false}
                dataKey="time"
                interval="preserveStartEnd"
                minTickGap={24}
                tick={{ fontSize: 11, fill: "#a1a1aa" }}
                tickLine={false}
              />
              <YAxis
                axisLine={false}
                tick={{ fontSize: 11, fill: "#a1a1aa" }}
                tickFormatter={axisBytes}
                tickLine={false}
                width={52}
              />
              <Tooltip content={<ChartTooltip />} />
              {keys.map((key) => (
                <Area
                  key={key}
                  activeDot={{ r: 4, strokeWidth: 0 }}
                  animationDuration={800}
                  dataKey={key}
                  fill={`url(#traffic-${key})`}
                  stackId="traffic"
                  stroke={SERIES[key].color}
                  strokeWidth={2}
                  type="monotone"
                />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        </div>
        <Legend keys={keys} />
      </CardBody>
    </Card>
  );
}

/** Daily traffic for the last seven days as stacked bars. */
export function WeeklyTrafficChart({ daily }: { daily: FlowBucket[] }) {
  const data = [...daily].reverse().map((bucket) => ({
    time: dayLabel(bucket),
    ...splitBucket(bucket),
  }));
  const sum = totals(daily);
  const keys: SeriesKey[] =
    sum.unsplit > 0 ? ["inFlow", "outFlow", "unsplit"] : ["inFlow", "outFlow"];

  return (
    <Card className={cardClass}>
      <CardHeader className="flex items-start justify-between gap-3 p-5 pb-0">
        <div>
          <h2 className="text-base font-semibold text-foreground">本周流量</h2>
          <p className="mt-0.5 text-sm text-default-500">最近 7 天</p>
        </div>
        <p className="text-lg font-semibold text-primary">
          {formatBytes(sum.total)}
        </p>
      </CardHeader>
      <CardBody className="gap-3 p-5 pt-4">
        <div className="h-48 w-full">
          <ResponsiveContainer height="100%" width="100%">
            <BarChart
              data={data}
              margin={{ top: 5, right: 0, left: -4, bottom: 0 }}
            >
              <CartesianGrid
                stroke="currentColor"
                strokeOpacity={0.08}
                vertical={false}
              />
              <XAxis
                axisLine={false}
                dataKey="time"
                tick={{ fontSize: 11, fill: "#a1a1aa" }}
                tickLine={false}
              />
              <YAxis
                axisLine={false}
                tick={{ fontSize: 11, fill: "#a1a1aa" }}
                tickFormatter={axisBytes}
                tickLine={false}
                width={52}
              />
              <Tooltip
                content={<ChartTooltip />}
                cursor={{ fill: "currentColor", fillOpacity: 0.05 }}
              />
              {keys.map((key, index) => (
                <Bar
                  key={key}
                  animationDuration={800}
                  dataKey={key}
                  fill={SERIES[key].color}
                  maxBarSize={22}
                  radius={index === keys.length - 1 ? [4, 4, 0, 0] : 0}
                  stackId="traffic"
                />
              ))}
            </BarChart>
          </ResponsiveContainer>
        </div>
        <Legend keys={keys} />
      </CardBody>
    </Card>
  );
}

/** Today's traffic as a ring split by direction, with the total in the middle. */
export function TodayTrafficCard({ today }: { today?: FlowBucket }) {
  const split = today
    ? splitBucket(today)
    : { inFlow: 0, outFlow: 0, unsplit: 0 };
  const total = today?.flow || 0;
  const keys = (["inFlow", "outFlow", "unsplit"] as SeriesKey[]).filter(
    (key) => key !== "unsplit" || split.unsplit > 0,
  );
  const slices = keys.map((key) => ({ key, value: split[key] }));

  return (
    <Card className={cardClass}>
      <CardHeader className="p-5 pb-0">
        <div>
          <h2 className="text-base font-semibold text-foreground">今日流量</h2>
          <p className="mt-0.5 text-sm text-default-500">从今天 0 点起</p>
        </div>
      </CardHeader>
      <CardBody className="flex flex-row items-center gap-4 p-5 pt-2">
        <div className="relative h-44 w-44 shrink-0">
          <ResponsiveContainer height="100%" width="100%">
            <PieChart>
              <Pie
                data={total > 0 ? slices : [{ key: "empty", value: 1 }]}
                animationDuration={800}
                dataKey="value"
                innerRadius="72%"
                isAnimationActive={total > 0}
                outerRadius="100%"
                paddingAngle={total > 0 && slices.length > 1 ? 2 : 0}
                stroke="none"
              >
                {(total > 0 ? slices : [{ key: "empty" }]).map((slice) => (
                  <Cell
                    key={slice.key}
                    fill={
                      slice.key === "empty"
                        ? "rgba(161,161,170,0.2)"
                        : SERIES[slice.key as SeriesKey].color
                    }
                  />
                ))}
              </Pie>
            </PieChart>
          </ResponsiveContainer>
          <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-xs text-default-500">合计</span>
            <span className="text-base font-semibold text-foreground">
              {formatBytes(total)}
            </span>
          </div>
        </div>
        <div className="min-w-0 flex-1 space-y-3">
          {keys.map((key) => (
            <div key={key} className="min-w-0">
              <div className="flex items-center gap-1.5 text-xs text-default-500">
                <span
                  className="h-2 w-2 rounded-full"
                  style={{ background: SERIES[key].color }}
                />
                {SERIES[key].label}
              </div>
              <p className="mt-0.5 truncate text-sm font-semibold text-foreground">
                {formatBytes(split[key])}
              </p>
            </div>
          ))}
        </div>
      </CardBody>
    </Card>
  );
}
