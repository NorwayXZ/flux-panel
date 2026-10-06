/**
 * 响应式断点 Hook
 *
 * 提供响应式断点检测，方便在组件中根据屏幕尺寸调整行为
 */

import { useState, useEffect } from 'react';

const breakpoints = {
  sm: 640,
  md: 768,
  lg: 1024,
  xl: 1280,
  '2xl': 1536,
} as const;

export type Breakpoint = keyof typeof breakpoints;

export interface UseBreakpointReturn {
  /** 当前断点 */
  current: Breakpoint;
  /** 检查是否大于等于指定断点 */
  isAbove: (breakpoint: Breakpoint) => boolean;
  /** 检查是否小于指定断点 */
  isBelow: (breakpoint: Breakpoint) => boolean;
  /** 是否为移动端 (< md) */
  isMobile: boolean;
  /** 是否为平板 (>= md && < lg) */
  isTablet: boolean;
  /** 是否为桌面端 (>= lg) */
  isDesktop: boolean;
  /** 当前屏幕宽度 */
  width: number;
}

/**
 * 响应式断点 Hook
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const { isMobile, isDesktop, current } = useBreakpoint();
 *
 *   return (
 *     <div>
 *       {isMobile && <MobileView />}
 *       {isDesktop && <DesktopView />}
 *       <p>当前断点: {current}</p>
 *     </div>
 *   );
 * }
 * ```
 */
export function useBreakpoint(): UseBreakpointReturn {
  const [width, setWidth] = useState(() => {
    if (typeof window !== 'undefined') {
      return window.innerWidth;
    }
    return 1024; // 默认值
  });

  const [currentBreakpoint, setCurrentBreakpoint] = useState<Breakpoint>(() => {
    if (typeof window !== 'undefined') {
      return getBreakpoint(window.innerWidth);
    }
    return 'lg';
  });

  useEffect(() => {
    const updateBreakpoint = () => {
      const newWidth = window.innerWidth;
      setWidth(newWidth);
      setCurrentBreakpoint(getBreakpoint(newWidth));
    };

    // 使用 requestAnimationFrame 节流
    let rafId: number | null = null;
    const handleResize = () => {
      if (rafId) {
        cancelAnimationFrame(rafId);
      }
      rafId = requestAnimationFrame(updateBreakpoint);
    };

    window.addEventListener('resize', handleResize);

    // 初始化
    updateBreakpoint();

    return () => {
      window.removeEventListener('resize', handleResize);
      if (rafId) {
        cancelAnimationFrame(rafId);
      }
    };
  }, []);

  const isAbove = (breakpoint: Breakpoint) => {
    return breakpoints[currentBreakpoint] >= breakpoints[breakpoint];
  };

  const isBelow = (breakpoint: Breakpoint) => {
    return breakpoints[currentBreakpoint] < breakpoints[breakpoint];
  };

  const isMobile = !isAbove('md');
  const isTablet = isAbove('md') && isBelow('lg');
  const isDesktop = isAbove('lg');

  return {
    current: currentBreakpoint,
    isAbove,
    isBelow,
    isMobile,
    isTablet,
    isDesktop,
    width,
  };
}

/**
 * 根据宽度获取对应的断点
 */
function getBreakpoint(width: number): Breakpoint {
  if (width >= breakpoints['2xl']) return '2xl';
  if (width >= breakpoints.xl) return 'xl';
  if (width >= breakpoints.lg) return 'lg';
  if (width >= breakpoints.md) return 'md';
  return 'sm';
}

/**
 * 媒体查询 Hook
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const isDarkMode = useMediaQuery('(prefers-color-scheme: dark)');
 *   const isLandscape = useMediaQuery('(orientation: landscape)');
 *
 *   return <div>暗色模式: {isDarkMode ? '是' : '否'}</div>;
 * }
 * ```
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window !== 'undefined') {
      return window.matchMedia(query).matches;
    }
    return false;
  });

  useEffect(() => {
    const mediaQuery = window.matchMedia(query);

    const handleChange = (e: MediaQueryListEvent) => {
      setMatches(e.matches);
    };

    // 现代浏览器使用 addEventListener
    if (mediaQuery.addEventListener) {
      mediaQuery.addEventListener('change', handleChange);
      return () => mediaQuery.removeEventListener('change', handleChange);
    } else {
      // 兼容旧浏览器
      mediaQuery.addListener(handleChange);
      return () => mediaQuery.removeListener(handleChange);
    }
  }, [query]);

  return matches;
}

/**
 * 视口尺寸 Hook
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const { width, height } = useViewport();
 *
 *   return <div>视口大小: {width} x {height}</div>;
 * }
 * ```
 */
export function useViewport() {
  const [viewport, setViewport] = useState(() => {
    if (typeof window !== 'undefined') {
      return {
        width: window.innerWidth,
        height: window.innerHeight,
      };
    }
    return { width: 1024, height: 768 };
  });

  useEffect(() => {
    let rafId: number | null = null;

    const handleResize = () => {
      if (rafId) {
        cancelAnimationFrame(rafId);
      }
      rafId = requestAnimationFrame(() => {
        setViewport({
          width: window.innerWidth,
          height: window.innerHeight,
        });
      });
    };

    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      if (rafId) {
        cancelAnimationFrame(rafId);
      }
    };
  }, []);

  return viewport;
}

/**
 * 设备检测 Hook
 */
export function useDevice() {
  const { isMobile, isTablet, isDesktop } = useBreakpoint();
  const isTouch = useMediaQuery('(hover: none) and (pointer: coarse)');
  const hasHover = useMediaQuery('(hover: hover)');

  return {
    isMobile,
    isTablet,
    isDesktop,
    isTouch,
    hasHover,
    /** 是否为触摸设备 */
    isTouchDevice: isTouch,
    /** 是否支持悬浮 */
    supportsHover: hasHover,
  };
}
