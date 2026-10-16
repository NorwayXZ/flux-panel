import { useEffect, useRef, useState } from "react";

/**
 * A speedometer in the style of speedtest.net: a 270° arc with a non-linear Mbps scale,
 * a gradient fill and a needle that eases towards the live value.
 */

const TICKS = [0, 10, 50, 100, 250, 500, 1000, 2500, 5000, 10000];
const tickLabel = (value: number) =>
  value >= 1000 ? `${value / 1000}G` : String(value);

const START_ANGLE = 135;
const SWEEP = 270;

/** Maps Mbps to 0..1 along the arc; every tick interval gets an equal share. */
export const gaugeFraction = (mbps: number) => {
  if (!Number.isFinite(mbps) || mbps <= 0) return 0;
  const last = TICKS[TICKS.length - 1];

  if (mbps >= last) return 1;
  const step = 1 / (TICKS.length - 1);

  for (let index = 1; index < TICKS.length; index++) {
    if (mbps <= TICKS[index]) {
      const low = TICKS[index - 1];
      const high = TICKS[index];

      return step * (index - 1 + (mbps - low) / (high - low));
    }
  }

  return 1;
};

/** Eases a number towards its target every animation frame. */
export function useAnimatedNumber(target: number, smoothing = 0.18) {
  const [value, setValue] = useState(target);
  const current = useRef(target);

  useEffect(() => {
    let frame = 0;
    const tick = () => {
      const delta = target - current.current;

      if (Math.abs(delta) < Math.max(0.01, Math.abs(target) * 0.001)) {
        current.current = target;
        setValue(target);

        return;
      }
      current.current += delta * smoothing;
      setValue(current.current);
      frame = requestAnimationFrame(tick);
    };

    frame = requestAnimationFrame(tick);

    return () => cancelAnimationFrame(frame);
  }, [target, smoothing]);

  return value;
}

const polar = (cx: number, cy: number, radius: number, angle: number) => {
  const radians = (angle * Math.PI) / 180;

  return {
    x: cx + radius * Math.cos(radians),
    y: cy + radius * Math.sin(radians),
  };
};

const arcPath = (cx: number, cy: number, radius: number) => {
  const start = polar(cx, cy, radius, START_ANGLE);
  const end = polar(cx, cy, radius, START_ANGLE + SWEEP);

  return `M ${start.x} ${start.y} A ${radius} ${radius} 0 1 1 ${end.x} ${end.y}`;
};

export const formatGaugeSpeed = (mbps: number) =>
  mbps >= 1000
    ? { value: (mbps / 1000).toFixed(2), unit: "Gbps" }
    : { value: mbps >= 100 ? mbps.toFixed(0) : mbps.toFixed(1), unit: "Mbps" };

export default function SpeedGauge({
  mbps,
  mode,
  label,
}: {
  mbps: number;
  mode: "download" | "upload" | "idle";
  label: string;
}) {
  const animated = useAnimatedNumber(mbps);
  const fraction = gaugeFraction(animated);
  const size = 320;
  const center = size / 2;
  const radius = 128;
  const arcLength = (Math.PI * 2 * radius * SWEEP) / 360;
  const needleAngle = START_ANGLE + SWEEP * fraction;
  const speed = formatGaugeSpeed(animated);
  const gradient =
    mode === "upload" ? ["#c084fc", "#7c3aed"] : ["#22d3ee", "#3b82f6"];

  return (
    <div className="relative mx-auto aspect-square w-full max-w-[320px]">
      <svg className="h-full w-full" viewBox={`0 0 ${size} ${size}`}>
        <defs>
          <linearGradient id={`gauge-${mode}`} x1="0" x2="1" y1="1" y2="0">
            <stop offset="0%" stopColor={gradient[0]} />
            <stop offset="100%" stopColor={gradient[1]} />
          </linearGradient>
          <filter height="140%" id="gauge-glow" width="140%" x="-20%" y="-20%">
            <feGaussianBlur result="blur" stdDeviation="4" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
        <path
          d={arcPath(center, center, radius)}
          fill="none"
          stroke="hsl(var(--heroui-default-200))"
          strokeLinecap="round"
          strokeWidth={18}
        />
        <path
          d={arcPath(center, center, radius)}
          fill="none"
          filter="url(#gauge-glow)"
          stroke={`url(#gauge-${mode})`}
          strokeDasharray={`${arcLength * fraction} ${arcLength}`}
          strokeLinecap="round"
          strokeWidth={18}
        />
        {TICKS.map((tick, index) => {
          const angle = START_ANGLE + (SWEEP * index) / (TICKS.length - 1);
          const point = polar(center, center, radius - 32, angle);
          const reached = animated >= tick && animated > 0;

          return (
            <text
              key={tick}
              className={reached ? "fill-foreground" : "fill-default-400"}
              dominantBaseline="middle"
              fontSize={12}
              fontWeight={reached ? 600 : 400}
              textAnchor="middle"
              x={point.x}
              y={point.y}
            >
              {tickLabel(tick)}
            </text>
          );
        })}
        <g
          style={{
            transform: `rotate(${needleAngle}deg)`,
            transformOrigin: `${center}px ${center}px`,
          }}
        >
          <path
            className="fill-foreground"
            d={`M ${center + radius - 44} ${center} L ${center + 6} ${center - 5} L ${center + 6} ${center + 5} Z`}
            opacity={0.85}
          />
        </g>
        <circle className="fill-foreground" cx={center} cy={center} r={8} />
        <circle className="fill-content1" cx={center} cy={center} r={3.5} />
      </svg>
      <div className="pointer-events-none absolute inset-x-0 bottom-[14%] flex flex-col items-center">
        <span className="text-4xl font-bold tabular-nums text-foreground sm:text-5xl">
          {speed.value}
        </span>
        <span className="mt-1 text-sm text-default-500">{speed.unit}</span>
        <span
          className={`mt-2 text-xs font-medium ${mode === "upload" ? "text-secondary" : "text-primary"}`}
        >
          {label}
        </span>
      </div>
    </div>
  );
}

/** The big round start button, with a slow pulse while waiting. */
export function GoButton({
  label,
  onPress,
}: {
  label: string;
  onPress: () => void;
}) {
  return (
    <div className="relative mx-auto flex aspect-square w-full max-w-[320px] items-center justify-center">
      <span className="absolute h-52 w-52 animate-ping rounded-full bg-primary/10 [animation-duration:2.4s]" />
      <span className="absolute h-60 w-60 rounded-full border border-primary/20" />
      <button
        className="relative flex h-48 w-48 items-center justify-center rounded-full bg-gradient-to-br from-cyan-400 to-primary-600 p-[3px] shadow-lg shadow-primary/30 transition-transform duration-200 hover:scale-105 active:scale-95"
        type="button"
        onClick={onPress}
      >
        <span className="flex h-full w-full items-center justify-center rounded-full bg-content1 text-3xl font-bold tracking-wider text-foreground">
          {label}
        </span>
      </button>
    </div>
  );
}
