# Releasing an APK

How a signed APK of the public build is made and published as a GitHub pre-release, for people who
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

## Versions

`versionName` is the release (`0.1.0`), and the tag is `v` + `versionName`. `versionCode` goes up by
one for every published build and never goes down; Play requires the same.

## Publishing

Rename the APK to `tategaki-cho-<versionName>.apk`, then:

```
gh release create v0.1.0 tategaki-cho-0.1.0.apk --prerelease --title "たてがき帖 0.1.0" --notes-file <notes>
```

The notes give the SHA-256 of the APK (`shasum -a 256`) and link to
`docs/install.md`.

Then point the download page at the new APK: in `site/index.html`, the button's `href`, its label's version, and
`downloadUrl` in the JSON-LD all name `releases/download/v<versionName>/tategaki-cho-<versionName>.apk`. (GitHub's
`/releases/latest` skips pre-releases, so a fixed "latest" link does not work while releases are pre-releases.) A
push to main that touches `site/` deploys the page.
