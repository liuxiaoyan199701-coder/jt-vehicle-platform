import type { JT1078Player } from '../player';

export type VueOnUnmounted = (cleanup: () => void) => void;

export function bindVuePlayerLifecycle(player: JT1078Player, onUnmounted: VueOnUnmounted): JT1078Player {
  onUnmounted(() => {
    void player.destroy();
  });
  return player;
}
