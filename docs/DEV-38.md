# Minis Dev 38: model search and message readability

## Changes

- Both advanced-memory model pickers support model/provider search. Embedding candidates are stably prioritized using advertised output modalities and conservative name recognition; remaining eligible models stay selectable. Recognition is not an API compatibility test.
- With a readable chat wallpaper, user/assistant text and thinking use rounded backplates. Light: white with opaque black text. Dark: #303030 with opaque white text. Tools and code palettes retain their own styles.
- One shared opacity slider (default 80%, 0–100%) lives in Appearance → Chat background. Wallpaper dimming and message opacity share one live preview. Without a wallpaper, existing appearance is preserved.
- Streaming fake-background fade is suppressed where it would flash over a translucent backplate; long-content fallback rendering inherits the same readable palette.

## Build and verification

Built on the configured private Linux server, not GitHub Actions. Baseline: `c83860a0aa13ad7253568c4d9abab02cb952d9a8`. Source files in `docs/dev-38-source-sha256.json` match the verified server inputs.

From a clean checkout, `python3 scripts/configure_server_dev.py` applies the Dev 38 build-only overrides: application ID `com.openminis.app.memorytest`, app label `Minis Dev`, versionCode 38, versionName `1.15-dev`, pinned NDK 28.0.13004108, debug server and self-updates disabled. The script intentionally produces an unsigned APK; signing is a separate private operation. Do not run the legacy wallpaper CI configuration script as well.

Prepare native dependencies as described in BUILDING.md, then run from `src/android`:

```sh
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testGithubDebugUnitTest \
  --tests 'com.openminis.app.memory.*' \
  --tests 'com.openminis.app.ui.chat.ChatBackgroundPolicyTest' \
  --tests 'com.openminis.app.ui.chat.ChatMessageBackplateStyleTest' \
  --tests 'com.openminis.app.ui.settings.MemoryModelPickerPolicyTest' \
  :app:assembleGithubDebug
```

48 targeted tests passed: memory 31, wallpaper 3, message backplate 4, picker 10; zero failures, errors or skipped tests. Successful build does not substitute for device testing of streaming, selection, light/dark themes or IME behavior.

Delivered signed APK: 76,036,732 bytes; SHA256 `0030dfe9932642908c932dc918bce84703274d3c4dd38c9ee7cfa8fdadfb93a6`.

## Stable Dev signing and migration

Future Dev updates retain the same application ID and stable signing identity while incrementing versionCode. Private keys/passwords are not committed, uploaded as artifacts or embedded in scripts. Certificate SHA256 (public identity only): `326c93f23a79713dfe6b4d8f2174b637c3493020ee3053eb3102c51a3d4e6bcb`.

Dev 38 cannot directly update the old version37 CI test build: its private signing key was not recoverable. The user chose a one-time backup-first migration, not automatic uninstall/data clearing. Verify backups and their restore coverage before removing the old Dev app. Wallpaper images need reselection; advanced-memory backup/restore semantics have not been fully validated, so lossless migration is not promised.

This source push does not merge main, publish a Release or launch a cloud build. The legacy GitHub workflow is now manual-only and does not carry the stable Dev signing key.
