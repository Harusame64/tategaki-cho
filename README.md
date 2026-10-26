# たてがき帖

An Android app for reading web novels in vertical text (縦書き), and for going on reading without a
signal.

- Works from 小説家になろう, カクヨム and 青空文庫: search and ranking through the official なろう API,
  カクヨム works shared or pasted in, 青空文庫 from its own list of works.
- Ruby, emphasis dots and upright numbers, laid out by the formatter of
  [AozoraEpub3](https://github.com/Harusame64/AozoraEpub3-JDK21) (`core/`).
- Only works on the user's shelf are fetched: the episode being read and the next few, one request at
  a time. Kept text stays inside the app; there is no export.
- The author and a link to the original are always one tap away.

This app is unofficial and is not affiliated with 小説家になろう, カクヨム or 青空文庫.

## Build

JDK 21 and the Android SDK (compileSdk 36).

```
./gradlew :app:assemblePublicDebug      # the store build
./gradlew :app:assemblePersonalDebug    # the personal build (see docs/design.md, "Two builds")
./gradlew :core:test :app:testPublicE2eUnitTest
```

How the app is built: `docs/design.md`. The E2E scenarios: `docs/e2e.md`. The guide book bundled
with the app: `app/src/main/assets/guide.txt`.

## Licence

GPLv3, as the formatter it builds on: the licence text is `LICENSE`, the copyright notice `NOTICE`. Bundled fonts and libraries:
`THIRD-PARTY-NOTICES.txt`.
