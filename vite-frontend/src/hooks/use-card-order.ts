import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { getLayoutOrder, saveLayoutOrder } from '@/api';
import { JwtUtil } from '@/utils/jwt';

const normalizeIds = (ids: Array<string | number>): string[] => (
  Array.from(new Set(ids.map(String).filter(Boolean)))
);

const mergeOrder = (availableIds: string[], savedOrder: string[]): string[] => {
  const available = new Set(availableIds);
  const validSaved = savedOrder.filter(id => available.has(id));
  const saved = new Set(validSaved);
  const newItems = availableIds.filter(id => !saved.has(id));
  return [...newItems, ...validSaved];
};

const readLocalOrder = (key: string): string[] => {
  try {
    const value = localStorage.getItem(key);
    return value ? normalizeIds(JSON.parse(value)) : [];
  } catch {
    return [];
  }
};

export function useCardOrder(scope: string, itemIds: Array<string | number>) {
  const idsKey = itemIds.map(String).join('|');
  const normalizedIds = useMemo(() => normalizeIds(itemIds), [idsKey]);
  const userId = JwtUtil.getUserIdFromToken() ?? 0;
  const storageKey = `flux-card-order:${userId}:${scope}`;
  const [order, setOrder] = useState<string[]>(() => mergeOrder(normalizedIds, readLocalOrder(storageKey)));
  const orderRef = useRef(order);
  const interactionRevisionRef = useRef(0);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  const enqueueRemoteSave = useCallback((nextOrder: string[]) => {
    saveQueueRef.current = saveQueueRef.current
      .catch(() => undefined)
      .then(async () => {
        const response = await saveLayoutOrder(scope, nextOrder);
        if (response.code !== 0) {
          console.warn('保存卡片顺序失败:', response.msg);
        }
      })
      .catch(error => {
        console.warn('保存卡片顺序失败:', error);
      });
  }, [scope]);

  const commitOrder = useCallback((nextOrder: string[], syncRemote = true) => {
    interactionRevisionRef.current += 1;
    orderRef.current = nextOrder;
    setOrder(nextOrder);
    try {
      localStorage.setItem(storageKey, JSON.stringify(nextOrder));
    } catch {
      // Database persistence still works when browser storage is unavailable.
    }

    if (syncRemote) {
      enqueueRemoteSave(nextOrder);
    }
  }, [enqueueRemoteSave, storageKey]);

  useEffect(() => {
    const localOrder = readLocalOrder(storageKey);
    const initialOrder = mergeOrder(normalizedIds, localOrder);
    orderRef.current = initialOrder;
    setOrder(initialOrder);

    let active = true;
    const requestRevision = interactionRevisionRef.current;
    void getLayoutOrder(scope).then(response => {
      if (!active || response.code !== 0 || interactionRevisionRef.current !== requestRevision) return;
      const remoteOrder = Array.isArray(response.data) ? normalizeIds(response.data) : [];
      const nextOrder = mergeOrder(normalizedIds, remoteOrder.length > 0 ? remoteOrder : localOrder);
      orderRef.current = nextOrder;
      setOrder(nextOrder);
      try {
        localStorage.setItem(storageKey, JSON.stringify(nextOrder));
      } catch {
        // Ignore unavailable browser storage.
      }
      if (remoteOrder.length === 0 && localOrder.length > 0) {
        enqueueRemoteSave(nextOrder);
      }
    }).catch(() => {
      // Local order remains the offline fallback.
    });

    return () => {
      active = false;
    };
  }, [enqueueRemoteSave, scope, storageKey, idsKey]);

  const orderedIds = useMemo(
    () => mergeOrder(normalizedIds, order),
    [idsKey, order]
  );

  const sortItems = useCallback(<T,>(items: T[], getId: (item: T) => string | number): T[] => {
    const rank = new Map(orderedIds.map((id, index) => [id, index]));
    return [...items].sort((left, right) => (
      (rank.get(String(getId(left))) ?? Number.MAX_SAFE_INTEGER)
      - (rank.get(String(getId(right))) ?? Number.MAX_SAFE_INTEGER)
    ));
  }, [orderedIds]);

  const moveCard = useCallback((activeId: string | number, overId: string | number, visibleIds?: Array<string | number>) => {
    const activeKey = String(activeId);
    const overKey = String(overId);
    if (activeKey === overKey) return;

    const baseOrder = mergeOrder(normalizedIds, orderRef.current);
    const allowed = new Set((visibleIds ?? normalizedIds).map(String));
    if (!allowed.has(activeKey) || !allowed.has(overKey)) return;

    const visibleOrder = baseOrder.filter(id => allowed.has(id));
    const oldIndex = visibleOrder.indexOf(activeKey);
    const newIndex = visibleOrder.indexOf(overKey);
    if (oldIndex < 0 || newIndex < 0) return;

    const movedVisibleOrder = [...visibleOrder];
    const [moved] = movedVisibleOrder.splice(oldIndex, 1);
    movedVisibleOrder.splice(newIndex, 0, moved);

    let visibleIndex = 0;
    const nextOrder = baseOrder.map(id => (
      allowed.has(id) ? movedVisibleOrder[visibleIndex++] : id
    ));
    commitOrder(nextOrder);
  }, [commitOrder, idsKey]);

  return {
    orderedIds,
    sortItems,
    moveCard,
  };
}
