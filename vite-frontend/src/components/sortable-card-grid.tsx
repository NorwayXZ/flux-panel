import type { ReactNode } from "react";

import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  rectSortingStrategy,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { Grip } from "lucide-react";

interface SortableCardGridProps<T> {
  items: T[];
  getId: (item: T) => string | number;
  onMove: (
    activeId: string | number,
    overId: string | number,
    visibleIds: Array<string | number>,
  ) => void;
  renderItem: (item: T, dragHandle: ReactNode) => ReactNode;
  className?: string;
}

interface SortableCardItemProps<T> {
  item: T;
  itemId: string;
  renderItem: (item: T, dragHandle: ReactNode) => ReactNode;
}

function SortableCardItem<T>({
  item,
  itemId,
  renderItem,
}: SortableCardItemProps<T>) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: itemId });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 30 : undefined,
  };

  const dragHandle = (
    <button
      aria-label="拖动卡片调整位置"
      className="inline-flex h-7 w-7 flex-shrink-0 touch-none items-center justify-center rounded-md text-default-400 transition-colors hover:bg-default-100 hover:text-default-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      title="拖动调整位置"
      type="button"
      {...attributes}
      {...listeners}
    >
      <Grip aria-hidden="true" size={16} strokeWidth={2} />
    </button>
  );

  return (
    <div
      ref={setNodeRef}
      className={`h-full min-w-0 ${isDragging ? "opacity-80 drop-shadow-xl" : ""}`}
      style={style}
    >
      {renderItem(item, dragHandle)}
    </div>
  );
}

export function SortableCardGrid<T>({
  items,
  getId,
  onMove,
  renderItem,
  className = "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5",
}: SortableCardGridProps<T>) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );
  const visibleIds = items.map((item) => String(getId(item)));

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    onMove(active.id, over.id, visibleIds);
  };

  return (
    <DndContext
      collisionDetection={closestCenter}
      sensors={sensors}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={visibleIds} strategy={rectSortingStrategy}>
        <div className={className}>
          {items.map((item) => {
            const itemId = String(getId(item));

            return (
              <SortableCardItem
                key={itemId}
                item={item}
                itemId={itemId}
                renderItem={renderItem}
              />
            );
          })}
        </div>
      </SortableContext>
    </DndContext>
  );
}
