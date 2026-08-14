import { useCallback, useEffect, useState } from "react";

import { getMonitoringUnreadCount } from "@/api";

const ALERTS_UPDATED_EVENT = "flux-monitoring-alerts-updated";
const CACHE_TTL_MS = 5000;

let cachedCount: { value: number; expiresAt: number } | null = null;
let countRequest: Promise<number | null> | null = null;

const loadAlertCount = async (force = false): Promise<number | null> => {
  if (!force && cachedCount && cachedCount.expiresAt > Date.now()) {
    return cachedCount.value;
  }
  if (countRequest) return countRequest;

  countRequest = getMonitoringUnreadCount()
    .then((response) => {
      if (response.code !== 0) return null;
      const value = Number(response.data) || 0;

      cachedCount = { value, expiresAt: Date.now() + CACHE_TTL_MS };

      return value;
    })
    .catch(() => null)
    .finally(() => {
      countRequest = null;
    });

  return countRequest;
};

export const notifyAlertCountChanged = () => {
  cachedCount = null;
  window.dispatchEvent(new Event(ALERTS_UPDATED_EVENT));
};

export const useAlertUnreadCount = () => {
  const [count, setCount] = useState(0);

  const refresh = useCallback(async (force = false) => {
    const value = await loadAlertCount(force);

    if (value !== null) setCount(value);
  }, []);

  useEffect(() => {
    refresh();
    const timer = window.setInterval(() => refresh(), 30000);
    const handleAlertUpdate = () => {
      void refresh(true);
    };

    window.addEventListener(ALERTS_UPDATED_EVENT, handleAlertUpdate);

    return () => {
      window.clearInterval(timer);
      window.removeEventListener(ALERTS_UPDATED_EVENT, handleAlertUpdate);
    };
  }, [refresh]);

  return { count, refresh };
};
