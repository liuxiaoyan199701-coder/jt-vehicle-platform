import type { Component } from 'vue';
import LiveMapView from './views/live-map-view.vue';
import TrackMapView from './views/track-map-view.vue';
import ChartView from './views/chart-view.vue';
import LiveVideoView from './views/live-video-view.vue';
import PhotoGalleryView from './views/photo-gallery-view.vue';

/**
 * 视图类型到组件的映射。
 *
 * <p><b>这是前端自持的白名单</b>，与动作卡片里的接口路由表同源同理由：服务端下发的事件只说
 * 「展示哪一类视图」，究竟渲染哪个组件、调哪个接口取数，全部由这里决定。模型不该有能力指定
 * 调用哪个地址，也不该有能力指定怎么渲染。
 *
 * <p>不在表里的类型一律不渲染——新增视图必须同时在这里登记，否则前端不认。这与后端的类型白名单
 * 构成两道独立的关口：任何一侧漏登记，结果都是「不显示」，而不是「显示一个不受控的东西」。
 */
export interface ViewDefinition {
  component: Component;
  /** 查看该类内容所需的权限码，与后端白名单里声明的一致。 */
  requiredPermission: string;
  /** 找不到组件时给用户看的名字。 */
  label: string;
  /** 卡片标题旁的图标。 */
  icon: string;
  /** 能否放大到侧边面板。视频只在面板里播，所以它也算「可放大」。 */
  enlargeable: boolean;
}

export const VIEW_REGISTRY: Record<string, ViewDefinition> = {
  live_map: {
    component: LiveMapView,
    requiredPermission: 'monitor:view',
    label: '实时位置',
    icon: 'mdi:map-marker-outline',
    enlargeable: true
  },
  track_map: {
    component: TrackMapView,
    requiredPermission: 'track:view',
    label: '行驶轨迹',
    icon: 'mdi:map-marker-path',
    enlargeable: true
  },
  chart: {
    component: ChartView,
    requiredPermission: 'ai:chat',
    label: '统计图表',
    icon: 'mdi:chart-line',
    enlargeable: true
  },
  live_video: {
    component: LiveVideoView,
    requiredPermission: 'video:play',
    label: '实时视频',
    icon: 'mdi:video-outline',
    enlargeable: true
  },
  photo_gallery: {
    component: PhotoGalleryView,
    requiredPermission: 'media:list',
    label: '抓拍照片',
    icon: 'mdi:image-multiple-outline',
    enlargeable: true
  }
};

export function lookupView(type: string): ViewDefinition | null {
  return VIEW_REGISTRY[type] ?? null;
}
