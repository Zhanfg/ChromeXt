# Desktop extension import — phase 1

The upstream ChromeXt extension implementation is still a debug-only prototype. It currently
runs an unpacked extension's `background.page` with an incomplete JavaScript stub, **not** a
Chromium extension host. Do not enable it for release builds merely by removing the DEBUG gate.

This branch adds an isolated ZIP ingestion primitive for a future file-picker/bridge:

- Reads a user-provided ZIP stream in the **browser process**, into ChromeXt's
  existing browser-scoped `Extension` directory.
- Requires a root-level `manifest.json` with manifest v2 or v3, name and version.
- Rejects path traversal, duplicate entries and malformed archives, with per-file,
  archive-total, manifest-size and entry-count limits.
- Stages extraction and commits only after validation; failed imports are cleaned up.
- Exposes `LocalFiles.importZip(stream)` for a future authenticated import action.
- Includes JVM tests and CI validation.

## Important limitations

- This is **not** a UI, CRX/Chrome Web Store installer, native browser integration or complete
  extension runtime. Imported ZIPs are **not executed** by the installer.
- Manifest V3 service workers, isolated worlds, `chrome.*` APIs, extension permissions,
  host permissions, webRequest/declarativeNetRequest and updates remain unimplemented.
- Imported local directory IDs are **not** Chrome Web Store IDs. We must not misrepresent
  them as such.
- The original `LocalFiles` HTTP prototype must be audited and secured before exposing it
  in production. Do not enable it in release yet.
- ZIPs must have `manifest.json` at the archive root, not inside a wrapper folder.
- A production UI should accept SAF `content://` streams within the target browser process;
  the separate ChromeXt APK cannot write directly into another application's scoped storage.
- Explicit permission review and per-extension disable/delete controls are required before
  untrusted third-party extensions can execute.

## Follow-up acceptance criteria

1. Add a browser-process SAF picker, confirmation dialog and import command.
2. Add extension metadata listing, disable/delete and rollback, without activating scripts.
3. Introduce a capability-gated MV3 runtime and test a known extension against each API.
4. Only then enable the vetted extension flow in release builds.

## Phase 2: browser-process extension manager

Chrome overflow menu → Extensions (preview) → Import extension ZIP. Chrome launches
Android's Storage Access Framework. Imported packages are shown as **inactive**, and can be
inspected or deleted after confirmation. No system-wide storage permission is requested.

The request-code result hook handles the active Chrome Activity and its inherited
onActivityResult overrides, including the newer overload. Activity recreation while the
system picker is open still needs a device test and may require a lifecycle integration.

The experimental HTTP file server is not started by this flow. CRX packages, Chrome Web
Store installs, manifest v3 service workers, Chrome extension APIs, host permissions, and
background execution are NOT supported. Android 17/Chrome device verification is pending.
