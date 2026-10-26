# たてがき帖 — system design

How the app is built, as of 2026-10-26. For the scenarios the
E2E tests cover, see `e2e.md`; for licences, `NOTICE`, `LICENSE` and `THIRD-PARTY-NOTICES.txt`.

## What it is

An Android reader that shows web novels in vertical text (縦書き), and keeps working
without a signal. It reads from three sources:

| Source | How a work gets onto the shelf | Where the text comes from |
|---|---|---|
| 小説家になろう | search and ranking (official なろう API), share from a browser, paste a URL | the episode page on `ncode.syosetu.com`, one at a time |
| カクヨム | share from a browser, paste a URL (no search or ranking) | the work page (list of episodes) and each episode page on `kakuyomu.jp` |
| 青空文庫 | browse and search the official list of works, kept on the phone | the work's ruby text zip on `www.aozora.gr.jp`, fetched once |

It is not a download or export tool: there is no EPUB or text export, and kept text stays
inside the app (see "Policy built into the code").

## Modules

```
core/   plain Java (no Android): aozora's formatter, copied from AozoraEpub3-JDK21 (core/ORIGIN.md)
        + jp.harusame.aozorareader.core: EpisodeFormatter, AozoraFormatter, SafeXhtml,
          XhtmlSections, EpisodeInfo, KakuyomuWork, Toc
app/    the Android app (Java, minSdk 33, targetSdk 36), one WebView page for reading
art/    the icon source (SVG) and the 512px Play icon
docs/   this file, e2e.md
```

### core

- **EpisodeFormatter**: an episode page's HTML → aozora's web extraction
  (`web/<site>/extract.txt`) → Aozora text → **AozoraFormatter** → XHTML sections.
  Illustrations are skipped: the converter would fetch them itself, outside the app's network door.
- **AozoraFormatter**: Aozora text → vertical XHTML. Options: `removeEmptyLines` (空行),
  `forceIndent` (強制字下げ).
- **SafeXhtml**: cuts the formatter's output down to text structure, ruby and the formatter's
  classes. An author's literal markup comes out of the conversion as real tags, and the page has
  script and a bridge to the app, so ids, links, images, styles and other attributes are dropped.

### app — screens

| Class | Screen |
|---|---|
| `ShelfActivity` | the shelf (tabs: shelf / search / 青空文庫), its menus, new-episode check, storage cleanup |
| `SearchActivity` | なろう search and ranking (official API) |
| `AozoraActivity` | 青空文庫: popular, by author, by category, search |
| `ReaderActivity` | the reader: a WebView with `assets/reader.html`, the bridge `Android.*` |
| `LogActivity` | the read log: a month (pages per day, the month summed up) or a year (its months and works); a back arrow and no tabs |
| `LicenceActivity` | ライセンス (from ⋮): the GPL notice and the source, the shipped libraries and fonts, each licence in full; the texts are the repository's, copied into the assets at build time |
| `ThemedActivity` | shared look and tabs |
| `App` | counts the screens shown (for 全話を保存) |

### app — data and network

| Class | Role |
|---|---|
| `Net` | **the only door to the network** (see below) |
| `Shelf` | the shelf (`shelf.json`): works, places, tags, news; every change through `Shelf.update` under `Shelf.class` |
| `Episodes` | where an episode is kept, and fetching one once (shelf works only) |
| `NarouApi` | the official なろう API: search, ranking, details of shelf works in one request |
| `KakuyomuSite` | カクヨム: the work page parsed into a list (`kk.json`), episodes by id |
| `AozoraSite` | 青空文庫: the official list of works (CSV zip) as a local index, a work's text, its sections |
| `CheckWorker` | WorkManager job: the new-episode check while the app is closed (off by default; 3/6/12/24 h) |
| `FullSave` | 全話を保存: every episode of one work, spaced, while the app is in front |
| `ReadLog` | pages read per day and work (`readlog.json`) |

## The network door (`Net`)

Every request from every screen and worker goes through `Net.get`:

- **https only, four hosts:** `ncode.syosetu.com`, `api.syosetu.com`, `kakuyomu.jp`,
  `www.aozora.gr.jp`. A redirect is followed only if it stays among them (at most 3).
- **One at a time, at least 1 s apart**, across the whole app.
- A request may carry a guard (`Net.get(url, wanted)`), asked again once its turn comes: 全話を
  保存 uses it so a fetch waiting behind another is not sent after 止める, removal, or leaving the app.
- **User-Agent:** `Tategakicho/0.1 (Android; personal vertical reader)`.
- No cookies, accounts, identifiers or analytics are sent; the request is the page URL and the
  User-Agent.

What is fetched, and when:

| What | When |
|---|---|
| なろう API (details of shelf works, one request for up to 500) | opening the shelf (at most hourly), 新着を確認, the background check |
| なろう API (search, ranking) | when the user searches or opens a ranking |
| カクヨム work page | adding a work; the new-episode check (one page per work, a second apart); opening an episode not in the kept list (once per episode per run) |
| episode page (なろう, カクヨム) | opening it, if not kept; read-ahead of the next 1/3/5 episodes (5 s after opening, one at a time, kept ones skipped); 全話を保存 |
| 青空文庫 list of works (2 MB zip) | first use of the 青空文庫 tab, then when 30 days old |
| 青空文庫 text zip | first time a work is read |

Only works on the shelf are fetched: `Episodes.fetch` checks the shelf before fetching and
again, under `Shelf.class`, before writing; a work removed meanwhile keeps nothing.

## Storage

All under the app's private `filesDir`:

```
shelf.json              the shelf
readlog.json            the read log
episodes/<key>/         kept text of one work
    <n>.html            なろう episode n (0.html for a short story)
    k<id>.html          カクヨム episode, by its id
    kk.json             カクヨム list of episodes
    sections.json       青空文庫 work, formatted into sections
works/<key>.json        青空文庫 work details (title, author, card, zip URL)
aozora/                 aozora's tables and site definitions, copied from core's resources
shared_prefs/reader.xml display settings (JSON in "display"), tabs, sort
```

Keys: なろう `n1234ab`, カクヨム `kakuyomu-<work id>`, 青空文庫 `aozora-<work id>`.
Internal URLs: なろう `https://ncode.syosetu.com/<ncode>/<n>/`, カクヨム
`kakuyomu://<work>/e/<episode id>`, 青空文庫 `aozora://<id>/<n>`.

Writes that must not be cut short (`shelf.json`, `sections.json`, the data tables, episodes) go
to a temporary file and are moved into place.

**Backup** (`res/xml/data_extraction_rules.xml`): Google backup and phone-to-phone transfer
carry `shelf.json`, `works/`, `readlog.json` and `reader.xml`; kept episodes are left out (a cache,
and the backup limit is 25 MB).

## Reading

`ReaderActivity` formats an episode (`prepare`) and hands the page the XHTML, the credit
(`INFO`) and the place. `reader.html` lays it out:

- **Pages:** one page = one column of a vertical-rl multicol box; a page turn scrolls one column.
  Lines are never cut in half. Margins (`PADX`, `PADY`) and line height (`--lh`) come from the
  display settings.
- **Taps:** left 40 % next page, right 40 % previous, middle 20 % the bars; swipes turn with the
  finger; volume keys turn when chosen.
- **目次:** chapters and episodes; episodes kept in the app are marked 保存済み, with a count
  (looked up as the 目次 is delivered).
- **Bars:** 本棚, 目次, 作品 (title, author, links to the original), Aa (settings), a position slider.
- **Aa settings:** background (5), typeface (4, two bundled OFL fonts), 空行, 字下げ (web novels
  only), text size, 行間, 上下余白, 左右余白, 触る場所, read-ahead, clock, volume keys.
  空行 and 字下げ change the formatting, so the episode is formatted again at the same share read.
- **First-time guide:** the tap zones, shown once (`guided` in the settings), again from Aa.
- **Across episodes:** the next and previous episodes are formatted ahead and swapped in place.
  At the end of a work, an end card.
- **Places:** the episode and page are saved to the shelf as pages turn and on pause; カクヨム
  places are kept by episode id, so an episode inserted or deleted by the author does not move them
  (`KakuyomuSite.remap`).

## Shelf

- **The guide book** 「たてがき帖の使い方」 (`assets/guide.txt`, Aozora notation) is put on a new shelf
  once (`Guide`). It is read as a 青空文庫 work under id 000000, which 青空文庫 does not use, with its text
  from the app itself: no request, a 目次, no links to a site. Taken off, it stays off; ＋ has
  使い方の本を本棚に戻す.
- **はじめかた**: on a shelf with nothing but the guide book, a card with 作品を探す, ランキング,
  青空文庫から探す and URL を貼って足す.

- Adding: share (`ACTION_SEND` text) or paste a URL (なろう, カクヨム), search/ranking (なろう),
  the 青空文庫 tab.
- **New-episode check:** opening the shelf (at most hourly) or 新着を確認, and optionally in the
  background (`CheckWorker`, with a notification). Works not found (deleted or private) are
  reported. In the personal build **their kept text is not deleted** (the user's decision, 2026-10-26:
  authors sometimes remove works when they are published as books); the store build drops it after a
  day (see "Two builds").
- Long-press a work: 共有, 作品ページを開く, タグ, 全話を保存 / 全話の保存を止める, 保存した話を消す,
  本棚から外す. Taking a work off the shelf deletes its kept text.
- ⋮: まとめて外す, 保存容量の整理 (per work: read episodes or all), 読書の記録, 新着の確認, ライセンス.

## 全話を保存 (`FullSave`)

- Every episode of one なろう or カクヨム work not kept yet, one at a time, 3 s apart (plus Net's
  1 s), on a single thread; works queue in the order asked.
- Goes on only while a screen of the app is shown (`App` counts started screens); otherwise it waits
  before each request, including the カクヨム list read at the start.
- Ends on 止める, on taking the work off the shelf, or on an error (pressing it again goes on,
  skipping what is kept). Every way of clearing kept text goes through `FullSave.prune`, which stops
  the save and waits for its fetch in flight.
- In the personal build only (`FullSave.ENABLED` from `BuildConfig.FULL_SAVE`; see "Two builds").

## Two builds

One source, two product flavors (`app/build.gradle`):

| | public | personal |
|---|---|---|
| for | Google Play | the family, by APK |
| applicationId | `jp.harusame.tategakicho` | `jp.harusame.tategakicho.personal` (beside the other) |
| name | たてがき帖 | たてがき帖（個人） |
| 全話を保存 | no | yes |
| a work gone from its site | its kept text is dropped on two answers from the work's own page, a day or more apart, that it is gone (404/410; カクヨム's check reads that page anyway; for なろう the page is asked when the API leaves the work out, at most daily, since a work out of search is not in the API but still readable); the work and place stay | kept text stays |

A work found gone is marked (`Work.missingSince`) and unmarked when found again.

## Tests

- JVM (`core`): formatter, sanitizer, blank lines, indent, カクヨム remap and URLs, …
  `./gradlew :core:test :app:testE2eUnitTest`
- E2E (instrumented, `app/src/androidTest`), against their own build
  (`jp.harusame.tategakicho.e2e`), with the network answered from fixtures behind the same host
  check, once per build: `./gradlew :app:connectedPublicE2eAndroidTest :app:connectedPersonalE2eAndroidTest`
  on an emulator. Scenarios in `e2e.md`.

## Build identity

- applicationId `jp.harusame.tategakicho` (personal: `.personal`); the Java package stays
  `jp.harusame.aozorareader`.
- Repository `Harusame64/tategaki-cho` (public), GPLv3; issues may be filed here.
- Icon: 帖 in Noto Serif JP (OFL) on indigo, adaptive with a monochrome layer; the notification
  icon uses the same outline.
