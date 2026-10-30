# Releasing an APK

How a signed APK of the public build is made and published as a GitHub release, for people who
install it by hand before the app is on Google Play. The testers' side is `install.md`.

## The key

One key signs every APK, for good: Android installs an update over an installed app only when both
are signed with the same key. Losing the key means no more updates (testers would have to uninstall
and lose their shelf); leaking it lets someone else publish updates as this app.

- Made once, with `keytool` (JDK), as an RSA 4096-bit key valid for 100 years:

  ```
  keytool -genkeypair -v -keystore <path>/tategaki-cho-release.jks -storetype PKCS12 -alias tategaki-cho -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Harusame64"
  ```

  keytool asks for the password itself, so it is never typed on the command line or kept in a
  shell history. (With PKCS12 the key's password is the keystore's.)
- Kept outside every repository, with a backup off the machine. Its password is kept with the
  backup, not in any file here.
- Google Play later: Play App Signing accepts a copy of an existing key as the app signing key for a
  new app, if it is given before any release reaches open testing or production ("Provide a copy of
  your app signing key", Play Console Help, *Use Play App Signing*). Doing that keeps this key as
  the signing key on Play too, so a tester can update from the APK to the Play version without
  uninstalling. Google recommends a separate upload key in that case.

## Building

The build reads four values, from environment variables or from `local.properties` (untracked):

| Name | Value |
|---|---|
| `TGC_KEYSTORE` | path of the `.jks` file |
| `TGC_KEYSTORE_PASSWORD` | its password |
| `TGC_KEY_ALIAS` | `tategaki-cho` |
| `TGC_KEY_PASSWORD` | the key's password (the same as the keystore's for PKCS12) |

A release build without all four stops and names the missing ones. Debug and E2E builds do not need
them.

```
./gradlew :app:assemblePublicRelease
```

The APK is `app/build/outputs/apk/public/release/app-public-release.apk`. Check it before
publishing:

```
apksigner verify --print-certs app-public-release.apk     # signed, with the release certificate
aapt2 dump badging app-public-release.apk                 # versionCode / versionName
```

## Screens on many devices

Before a release that changes anything on screen, look at every screen at the sizes of the phones and tablets most
sold in Japan (BCN rankings, 2026: moto g, arrows We, Pixel 10a/11, Galaxy S26 and A25, AQUOS wish/sense; tablets
1920x1200 such as Galaxy Tab A11+ and LAVIE Tab Lite):

```
./gradlew :app:installPublicE2e :app:installPublicE2eAndroidTest
ANDROID_SERIAL=emulator-5554 tools/screen-matrix.sh <out dir>      # kinari; add "kinari,dark" for both
```

Install right before running it: a Gradle E2E run uninstalls both apps when it ends, and then every screen is
reported as never ready (the script captures nothing it cannot vouch for, and exits nonzero).
Known limit: the test shows each screen for 3.5 s and the script captures after seeing its marker, with no handshake
back; on a very slow device the capture could fall behind and show the next screen under this one's name. The
pictures are looked at by a person, who will see it; if it happens, rerun on that profile (PROFILES=…).

Profiles: 360dp, 384dp and 411dp wide phones, the two narrow ones again at a 1.3x system font, and a tablet portrait
(800dp) and landscape (1280dp). Look for text cut off or spilling out of its button, labels breaking mid-word, and
anything oversized on a tablet. (Shipping 0.1.1 without this: buttons broke at 360dp with a 1.3x font, and the read
log's calendar filled a tablet.)

The emulator has Google's fonts only. A phone maker's own font (a Galaxy's, a changed system font) can be wider and
show what the emulator hides: 0.1.1's pill buttons had lost their side padding, which only the user's Galaxy A25
showed. With the user's agreement, install the E2E build on the A25 the same way (`ANDROID_SERIAL=<its serial>`), take
the screens (`-e themes kinari,dark` runs both), then remove it:
`adb uninstall jp.harusame.tategakicho.e2e.test; adb uninstall jp.harusame.tategakicho.e2e`. The user's own install
and shelf are a different app and are not touched.

## Versions

`versionName` is the release (`0.1.0`), and the tag is `v` + `versionName`. `versionCode` goes up by
one for every published build and never goes down; Play requires the same.

## Publishing

Rename the APK to `tategaki-cho-<versionName>.apk`, then:

```
gh release create v0.1.1 tategaki-cho-0.1.1.apk --latest --title "たてがき帖 0.1.1" --notes-file <notes>
```

The notes give the SHA-256 of the APK (`shasum -a 256`) and link to
`docs/install.md`.

Then point the download page at the new APK: in `site/index.html`, the button's `href`, its label's version, and
`downloadUrl` in the JSON-LD all name `releases/download/v<versionName>/tategaki-cho-<versionName>.apk` (the asset
name carries the version, so no fixed link reaches it). A push to main that touches `site/` deploys the page.
Releases are full releases marked Latest since 0.1.1 (0.1.0 went out as a pre-release); GitHub's
`/releases/latest` skips pre-releases.

## F-Droid

F-Droid builds the `public` release from the tag with `-Punsigned`, compares it with the signed APK of the same
version on GitHub, and, when they match apart from the signature, publishes the GitHub APK itself, signed with our
key (a reproducible build). So the two ways in, the download page and F-Droid, are one app: either updates the other
and the shelf stays. A version whose build does not match is not published on F-Droid.

- What has to hold: the APK on GitHub is built from the tagged commit with `assemblePublicRelease`, and the build
  writes no dependency list into the signing block (`dependenciesInfo` in `app/build.gradle`). 0.1.7 and earlier
  carry that list, so F-Droid starts at 0.1.8.
- Build the signed APK in a plain clone at the tag (not a `git worktree`: AGP then cannot read the commit and writes
  `NO_VALID_GIT_FOUND` into `META-INF/version-control-info.textproto`, where F-Droid's clone writes the commit).
- Before publishing, check the match the way F-Droid does: build the tag again with
  `./gradlew :app:assemblePublicRelease -Punsigned` in a second clean clone, then
  `apksigcopier compare <signed>.apk --unsigned app/build/outputs/apk/public/release/app-public-release-unsigned.apk`
  (`pipx install apksigcopier`). It copies our signature onto the unsigned build and verifies it, so the ZIP's order,
  metadata and compression must match too; comparing unzipped entries does not show those.
- The listing (name, descriptions, icon, screenshots, and `changelogs/<versionCode>.txt`) is read from
  `fastlane/metadata/android/`. Add the changelog for each new `versionCode` before tagging; the text is public.
- F-Droid notices a new tag `v<versionName>` on its own (it checks the tags) and builds it within a few days.
