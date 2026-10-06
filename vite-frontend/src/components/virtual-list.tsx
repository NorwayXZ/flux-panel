import { useVirtualizer } from '@tanstack/react-virtual';
import { useRef, useState, useEffect } from 'react';

interface VirtualListProps<T> {
  items: T[];
  renderItem: (item: T, index: number) => React.ReactNode;
  estimatedItemHeight?: number;
  overscan?: number;
  className?: string;
  emptyMessage?: string;
  getItemKey?: (item: T, index: number) => string | number;
}

/**
 * 虚拟滚动列表组件
 *
 * 用于优化长列表渲染性能，只渲染可见区域的项目
 *
 * @example
 * ```tsx
 * <VirtualList
 *   items={nodes}
 *   renderItem={(node) => <NodeCard node={node} />}
 *   estimatedItemHeight={100}
 * />
 * ```
 */
export function VirtualList<T>({
  items,
  renderItem,
  estimatedItemHeight = 80,
  overscan = 5,
  className = '',
  emptyMessage = '暂无数据',
  getItemKey,
}: VirtualListProps<T>) {
  const parentRef = useRef<HTMLDivElement>(null);

  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => estimatedItemHeight,
    overscan, // 预渲染可见区域外的项目数量
  });

  if (items.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-default-400">
        {emptyMessage}
      </div>
    );
  }

  return (
    <div
      ref={parentRef}
      className={`h-full overflow-auto ${className}`}
      style={{
        contain: 'strict',
      }}
    >
      <div
        style={{
          height: `${virtualizer.getTotalSize()}px`,
          width: '100%',
          position: 'relative',
        }}
      >
        {virtualizer.getVirtualItems().map((virtualItem) => {
          const item = items[virtualItem.index];
          const key = getItemKey
            ? getItemKey(item, virtualItem.index)
            : virtualItem.key;

          return (
            <div
              key={key}
              data-index={virtualItem.index}
              ref={virtualizer.measureElement}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${virtualItem.start}px)`,
              }}
            >
              {renderItem(item, virtualItem.index)}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * 虚拟网格组件
 *
 * 用于优化卡片网格布局的性能
 */
interface VirtualGridProps<T> {
  items: T[];
  renderItem: (item: T, index: number) => React.ReactNode;
  columnCount?: number;
  estimatedItemHeight?: number;
  gap?: number;
  className?: string;
  emptyMessage?: string;
}

export function VirtualGrid<T>({
  items,
  renderItem,
  columnCount = 3,
  estimatedItemHeight = 200,
  gap = 16,
  className = '',
  emptyMessage = '暂无数据',
}: VirtualGridProps<T>) {
  const parentRef = useRef<HTMLDivElement>(null);

  // 计算行数
  const rowCount = Math.ceil(items.length / columnCount);

  const virtualizer = useVirtualizer({
    count: rowCount,
    getScrollElement: () => parentRef.current,
    estimateSize: () => estimatedItemHeight + gap,
    overscan: 2,
  });

  if (items.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-default-400">
        {emptyMessage}
      </div>
    );
  }

  return (
    <div
      ref={parentRef}
      className={`h-full overflow-auto ${className}`}
      style={{
        contain: 'strict',
      }}
    >
      <div
        style={{
          height: `${virtualizer.getTotalSize()}px`,
          width: '100%',
          position: 'relative',
        }}
      >
        {virtualizer.getVirtualItems().map((virtualRow) => {
          const startIndex = virtualRow.index * columnCount;
          const rowItems = items.slice(startIndex, startIndex + columnCount);

          return (
            <div
              key={virtualRow.key}
              data-index={virtualRow.index}
              ref={virtualizer.measureElement}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                width: '100%',
                transform: `translateY(${virtualRow.start}px)`,
              }}
            >
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: `repeat(${columnCount}, 1fr)`,
                  gap: `${gap}px`,
                }}
              >
                {rowItems.map((item, colIndex) => (
                  <div key={startIndex + colIndex}>
                    {renderItem(item, startIndex + colIndex)}
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/**
 * 响应式虚拟网格
 * 自动根据容器宽度调整列数
 */
interface ResponsiveVirtualGridProps<T> extends Omit<VirtualGridProps<T>, 'columnCount'> {
  minCardWidth?: number; // 最小卡片宽度
}

export function ResponsiveVirtualGrid<T>({
  minCardWidth = 320,
  gap = 16,
  ...props
}: ResponsiveVirtualGridProps<T>) {
  const parentRef = useRef<HTMLDivElement>(null);
  const [columnCount, setColumnCount] = useState(1);

  useEffect(() => {
    const updateColumnCount = () => {
      if (parentRef.current) {
        const width = parentRef.current.offsetWidth;
        const cols = Math.max(1, Math.floor((width + gap) / (minCardWidth + gap)));
        setColumnCount(cols);
      }
    };

    updateColumnCount();
    window.addEventListener('resize', updateColumnCount);
    return () => window.removeEventListener('resize', updateColumnCount);
  }, [minCardWidth, gap]);

  return (
    <div ref={parentRef} className="h-full">
      <VirtualGrid {...props} columnCount={columnCount} gap={gap} />
    </div>
  );
}
