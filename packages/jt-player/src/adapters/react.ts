import type { JT1078Player } from '../player';

export function bindReactPlayerLifecycle(player: JT1078Player): () => void {
  return () => {
    void player.destroy();
  };
}
