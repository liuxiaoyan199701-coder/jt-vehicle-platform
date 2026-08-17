import type { RouteMeta } from 'vue-router';
import ElegantVueRouter from '@elegant-router/vue/vite';
import type { RouteKey } from '@elegant-router/types';

export function setupElegantRouter() {
  const routeMeta: Partial<Record<RouteKey, Partial<RouteMeta>>> = {
    home: { icon: 'mdi:monitor-dashboard', order: 1 },
    monitor: { icon: 'mdi:map-marker-radius', order: 2 },
    alarm: { icon: 'mdi:alert-outline', order: 3 },
    geofence: { icon: 'mdi:vector-circle', order: 4 },
    track: { icon: 'mdi:map-marker-path', order: 5 },
    fleet: { icon: 'mdi:garage-variant', order: 6 },
    vehicle: { icon: 'mdi:car-multiple', order: 7 },
    // permissions 写在这里而不是手改 src/router/elegant/routes.ts：
    // 那是生成产物，跑 pnpm gen-route 会被覆盖掉。
    ai: { icon: 'mdi:robot-outline', order: 8 },
    ai_chat: { icon: 'mdi:robot-outline', order: 1, permissions: ['ai:chat'] }
  };

  return ElegantVueRouter({
    layouts: {
      base: 'src/layouts/base-layout/index.vue',
      blank: 'src/layouts/blank-layout/index.vue'
    },
    routePathTransformer(routeName, routePath) {
      const key = routeName as RouteKey;

      if (key === 'login') {
        return '/login';
      }

      return routePath;
    },
    onRouteMetaGen(routeName) {
      const key = routeName as RouteKey;

      const constantRoutes: RouteKey[] = ['login', '403', '404', '500'];

      const meta: Partial<RouteMeta> = {
        title: key,
        i18nKey: `route.${key}` as App.I18n.I18nKey,
        ...routeMeta[key]
      };

      if (constantRoutes.includes(key)) {
        meta.constant = true;
      }

      return meta;
    }
  });
}
