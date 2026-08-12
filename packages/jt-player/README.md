# @jt/player

JT/T 1078 browser player SDK for the platform's raw `JT78` WebSocket stream.
It has no runtime dependencies and ships ESM, UMD, and TypeScript declarations.

## Usage

```ts
import { JT1078Player } from '@jt/player';

const player = new JT1078Player({
  openStreamUrl: 'https://gateway.example.com/stream/open',
  credential: () => sessionStorage.getItem('access_token') ?? undefined,
  canvas: document.querySelector('canvas')!
});

player.on('state', ({ state }) => console.log(state));
player.on('error', ({ code, message }) => console.error(code, message));
player.on('stats', (stats) => console.log(stats));

await player.play({ deviceId: '013800138000', channel: 1, streamKind: 'main' });
await player.startTalk();

await player.stopTalk();
await player.stop();
await player.destroy();
```

`play()` first posts `{ deviceId, channel, streamKind }` to `openStreamUrl`, then
connects directly to the returned absolute `wsUrl` with its one-time token. HTTP
redirect following is disabled and the SDK has no application-level redirect
state machine.

## Playback

```ts
await player.playback({
  deviceId: '013800138000',
  channel: 1,
  startTime: '2026-08-10T10:00:00Z',
  endTime: '2026-08-10T10:30:00Z'
});

player.pausePlayback();
player.seekPlayback('2026-08-10T10:12:00Z');
player.resumePlayback();
```

Live and playback frames use the same protocol, decoder, audio, and renderer
pipeline. Playback controls use the server contract
`{ "type": "playback-control", "action": ... }`. A custom
`playbackControlEncoder(command)` remains available for compatible gateways.

## Framework lifecycle adapters

The adapters intentionally do not import Vue or React, preserving zero runtime
dependencies.

```ts
// Vue 3 setup()
bindVuePlayerLifecycle(player, onUnmounted);

// React effect
useEffect(() => bindReactPlayerLifecycle(player), [player]);
```

## Binary frame contract

Each WebSocket binary message is one frame:

```
0..3  ASCII "JT78"
4     data type (I/P/B/audio/SPS/PPS/AudioConfig/VPS)
5     logical channel
6..7  flags/reserved
8..   raw payload
```

Text messages expose subscription state (`waking`, `live`, `failed`) before
binary media. Authentication failures and `DEVICE_NO_RESPONSE` are fatal for
that subscription and are not silently retried.
