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

## Bugfix: visible Extensions item does not open

- Handle the menu action by `R.id.extension_id` directly (no resource-name lookup).
- Route the actual Chrome Activity from the menu click into `ExtensionManager.show()`.
  Previously `Listener.on("extension")` could resolve an application or tab context,
  causing a silent no-op when the UI needed an Activity.
- Explicitly consume the module menu event and add tagged diagnostics.
- Lazy-load the incomplete JavaScript extension stub; importing and listing archives no
  longer depend on `extension.js` or an enriched AssetManager.
- Android 17 click-to-dialog and SAF result still require device verification.

- Use an item-only AlertDialog for the extension picker. Mixing `setMessage` and `setItems`
  caused the list to be hidden by the message view on Android.

## Phase 3: opt-in content-script execution

The new `ExtensionContentRuntime` connects user-approved MV3 `content_scripts` to ChromeXt's
existing navigation-time JavaScript injection. ZIPs remain **disabled by default**.
Open Extensions → imported extension → Enable content scripts → reload a matching page.

Supported now: `manifest_version: 3`, `content_scripts.matches`, `exclude_matches`,
local `js` and `css`, `document_idle` approximation, top-level HTTP(S) documents only.
Explicit safety confirmation is shown before execution. Importing a ZIP never activates it.

This is a **non-isolated, page-main-world compatibility fallback**, not a full WebExtensions
host. Other manifest features and `chrome.*` extension APIs are not implemented. The content
script can interact with the page DOM, but is **not** safe to run from untrusted packages.
All frames, native MV3 service workers, toolbar popups, store installation and all native
extension permissions remain unsupported. Chromium version/target device compatibility
is still unverified.

A smoke-test extension lives under `tests/fixtures/mv3-smoke`, and CI packages it as
`ChromeXt_MV3_Smoke.zip`. Install it via the Extension UI and explicitly enable it, visit
`https://example.com/`, reload: a small green `ChromeXt content script active` badge
should appear near the lower right if the navigation-to-JS bridge works on that Chrome build.

## Phase 4: isolated-world execution path

Manifest V3 content scripts now execute via `Page.getFrameTree`,
`Page.createIsolatedWorld` and `Runtime.evaluate(contextId)` over the Chromium DevTools
Protocol, instead of using `javascript:` URLs in the webpage's MAIN world. Each extension
gets a named execution world; execution failures do **not** fall back to the MAIN world.

This enables a separate JavaScript global while preserving access to page DOM. It is **not**
the complete Chromium extension host. There is no native WebExtension identity, API permission
enforcement, background Service Worker, or Web Store installation. Enabling a manifest that
declares privileged permissions, actions, or background execution now fails closed rather
than giving a misleading "supported" status.

CDP availability is a runtime requirement. When Chrome disables remote debugging or CDP
isolated worlds, the extension remains unable to execute. No unsafe fallback is attempted.
Navigation timing and lifecycle still require target Chrome / Android 17 testing.
