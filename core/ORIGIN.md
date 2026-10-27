# Where `core/` comes from

`core/src/main/java/com/github/hmdev/**` and `core/src/main/resources/aozora/**` are copied from
[AozoraEpub3-JDK21](https://github.com/AozoraEpub3-JDK21/AozoraEpub3-JDK21) at commit **f208cff2**
(v1.7.5-jdk21), itself based on hmdev/AozoraEpub3. GPLv3 — see `NOTICE` and `LICENSE`.

The copy is the set javac needed to compile the formatter (`converter`) and the web extraction
(`web`) on 2026-10-24: 52 source files in `config`, `converter`, `image`, `info`, `io`, `pipeline`,
`util`, `web`, `writer`.

This repository keeps its own copy on purpose (the user's decision, 2026-10-24): AozoraEpub3-JDK21
stays untouched, and if this app is dropped nothing is left behind there. Changes made here to
cut the three seams Android needs are listed below, so a later sync (either way) can find them.

## Seams cut in this copy

Measured with R8 against android.jar (2026-10-24): only six classes reference APIs Android lacks.

| Class | References | What | Here |
|---|---|---|---|
| image.ImageUtils | 76 (AWT, ImageIO) | illustrations | later (Bitmap) |
| info.ImageInfo | 52 (AWT, ImageIO) | illustrations | later |
| web.WebAozoraConverter | 36 (java.net.http) | fetch + extraction | fetch to be split out |
| writer.Epub3Writer | 16 (AWT, Swing) | EPUB output | not used (no EPUB export, by design) |
| util.LogAppender | 8 (Swing) | log pane | to be stubbed |
| info.BookInfo | 4 (AWT) | cover image | not used |

1. **fetch** — `web.WebAozoraConverter`: the shared `HttpClient` moved from the class's static
   initializer into a lazy holder (`Http.CLIENT`), so loading the class no longer touches
   `java.net.http`. New `episodeToAozoraText(Writer, Document)`: one already-fetched episode to
   Aozora text (no list page, no date lines, no leading page break).
2. **output** — new `writer.ConverterOutput`, the nine calls `converter.AozoraEpub3Converter` makes
   on its writer plus `progress(int)`. The converter takes the interface; `writer.Epub3Writer`
   implements it (the progress-bar code moved there from the converter). The reader's
   `jp.harusame.aozorareader.core.XhtmlSections` implements it to receive XHTML per section.
3. **log** — not cut yet (`util.LogAppender` reaches Swing); to be handled when it fails on a device.

## RAR removed (2026-10-26)

junrar is not a dependency here: the UnRAR licence forbids using its code to build a RAR-compatible
archiver, a further restriction GPLv3 does not allow (§7, §10). The app never opens an archive, so
the RAR branches were taken out rather than kept behind a flag. Each now throws an `IOException`
("RAR archives are not supported …") where it read a RAR file:

- `io.ArchiveCache.scan`, `image.ImageInfoReader.readImageInfoFromArchive` and `getImage`,
  `writer.Epub3ImageWriter.writeSections`, `writer.Epub3Writer` (the archive image copy);
- removed outright: `io.ArchiveScanner.scanRar`, `io.ArchiveTextExtractor.countRarText`,
  `image.ImageInfoReader.loadRarImageInfos`, and `RarException` from every `throws` and `catch`.
