import { useCallback, useEffect, useState } from 'react';

import { getMonitoringUnreadCount } from '@/api';

const ALERTS_UPDATED_EVENT = 'flux-monitoring-alerts-updated';

export const notifyAlertCountChanged = () => {
  window.dispatchEvent(new Event(ALERTS_UPDATED_EVENT));
};

export const useAlertUnreadCount = () => {
  const [count, setCount] = useState(0);

  const refresh = useCallback(async () => {
    try {
      const response = await getMonitoringUnreadCount();
      if (response.code === 0) {
        setCount(Number(response.data) || 0);
      }
    } catch {
      // Navigation should remain usable while monitoring is temporarily unavailable.
    }
  }, []);

  useEffect(() => {
    refresh();
    const timer = window.setInterval(refresh, 30000);
    window.addEventListener(ALERTS_UPDATED_EVENT, refresh);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener(ALERTS_UPDATED_EVENT, refresh);
    };
  }, [refresh]);

  return { count, refresh };
};
