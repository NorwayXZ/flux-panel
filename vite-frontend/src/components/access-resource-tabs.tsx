import { Tab, Tabs } from '@heroui/tabs';
import { Boxes, Globe2, Laptop, RefreshCw } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { isAdmin } from '@/utils/auth';

const resources = [
  { path: '/port-resources', label: '端口资源', icon: <Boxes size={16} />, adminOnly: true },
  { path: '/home-devices', label: '家庭设备', icon: <Laptop size={16} /> },
  { path: '/dns-settings', label: '域名管理', icon: <Globe2 size={16} />, adminOnly: true },
  { path: '/dynamic-dns', label: '动态解析', icon: <RefreshCw size={16} />, adminOnly: true },
];

export default function AccessResourceTabs() {
  const location = useLocation();
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);
  const visibleResources = resources.filter(item => !item.adminOnly || isAdmin());

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      scrollRef.current
        ?.querySelector<HTMLElement>('[role="tab"][data-selected="true"]')
        ?.scrollIntoView({ block: 'nearest', inline: 'center' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [location.pathname]);

  return (
    <div ref={scrollRef} className="resource-tabs-scroll max-w-full overflow-x-auto border-b border-divider">
      <Tabs
        aria-label="资源中心"
        classNames={{ base: 'w-max min-w-max', tabList: 'gap-1 px-0', tab: 'h-11 px-3 sm:px-4' }}
        selectedKey={location.pathname}
        variant="underlined"
        onSelectionChange={key => navigate(String(key))}
      >
        {visibleResources.map(item => (
          <Tab
            key={item.path}
            title={<span className="flex items-center gap-2 whitespace-nowrap">{item.icon}{item.label}</span>}
          />
        ))}
      </Tabs>
    </div>
  );
}
