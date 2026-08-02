type RouteLoader = () => Promise<unknown>;

const routeLoaders: Record<string, RouteLoader> = {
  '/change-password': () => import('@/pages/change-password'),
  '/dashboard': () => import('@/pages/dashboard'),
  '/forward': () => import('@/pages/forward'),
  '/nft-forward': () => import('@/pages/nft-forward'),
  '/tunnel': () => import('@/pages/tunnel'),
  '/node': () => import('@/pages/node'),
  '/user': () => import('@/pages/user'),
  '/profile': () => import('@/pages/profile'),
  '/config': () => import('@/pages/config'),
  '/update': () => import('@/pages/update'),
  '/monitoring': () => import('@/pages/monitoring'),
  '/service-publishing': () => import('@/pages/service-publishing'),
  '/topology': () => import('@/pages/topology'),
  '/port-resources': () => import('@/pages/port-resources'),
  '/cross-entry-failover': () => import('@/pages/cross-entry-failover'),
  '/source-ip-entry': () => import('@/pages/source-ip-entry'),
  '/smart-entry': () => import('@/pages/smart-entry'),
  '/dns-settings': () => import('@/pages/dns-settings'),
  '/private-proxy': () => import('@/pages/private-proxy'),
  '/network-tools': () => import('@/pages/network-tools'),
  '/quality-lab': () => import('@/pages/quality-lab'),
  '/bandwidth-test': () => import('@/pages/bandwidth-test'),
  '/virtual-lan': () => import('@/pages/virtual-lan'),
  '/server-assets': () => import('@/pages/server-assets'),
  '/dynamic-dns': () => import('@/pages/dynamic-dns'),
  '/home-access': () => import('@/pages/home-access'),
  '/home-devices': () => import('@/pages/home-devices'),
  '/guide': () => import('@/pages/guide'),
  '/system-self-check': () => import('@/pages/system-self-check'),
};

const startedRoutes = new Set<string>();

export const preloadRoute = (path: string) => {
  const route = path.split('?')[0];
  const loader = routeLoaders[route];
  if (!loader || startedRoutes.has(route)) return;

  startedRoutes.add(route);
  void loader().catch(() => {
    // A later navigation can retry if a transient asset request failed.
    startedRoutes.delete(route);
  });
};
