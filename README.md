# OpenChat

A clean, fast, local-first Android client for LLM endpoints — built for **OpenCode Go**.

OpenChat is a native Kotlin + Jetpack Compose app (no WebView, no JavaScript bridge) that streams responses token by token, understands images and documents, can search the web on-device, and keeps your API key encrypted in the Android Keystore.

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![APK](https://img.shields.io/badge/release%20APK-~8%20MB-success)

## Features

- **OpenCode Go native**: sends `Authorization`, a descriptive `User-Agent`, and a stable `x-opencode-session` header for every conversation.
- **Any endpoint you paste**: auto-detects OpenAI Chat Completions, OpenAI Responses, Anthropic Messages, and Gemini APIs.
- **Streaming chat** with stop, retry, copy and share.
- **Images** from camera or photo picker, downscaled on-device before upload.
- **Documents**: PDF, DOCX, PPTX, XLSX, HTML, Markdown, JSON, CSV, source code and plain text, extracted locally.
- **Local web tools**: `web_search` and `web_fetch` executed on the phone, with DuckDuckGo out of the box and optional Brave Search.
- **Markdown rendering** with tables, lists, quotes, links, syntax highlighting, LaTeX math (display and inline), and copy/save/share for code blocks.
- **Usage awareness**: token counts, cached tokens, cost, and OpenCode `allowance` are shown per reply.
- **Conversation management**: search, pin, rename, delete, export to Markdown/JSON.
- **Privacy first**: no analytics, no server, no backup of chats or keys; secrets are AES-GCM encrypted via the Android Keystore.
- **Edge-to-edge Material 3** UI with light/dark/system themes and a compact, ChatGPT-like layout.

## Requirements

- Android 8.0 (API 26) or newer.
- An [OpenCode Go](https://opencode.ai/v2/docs/console/go) subscription and a personal API key (`oc_sk_…`).
- For local builds: JDK 21 and Android SDK 35.

## Setup

1. Install the APK from the [Releases](../../releases) page or build it yourself.
2. Open **Settings** from the chat drawer.
3. Paste your endpoint. Common choices:

   | What you paste | Detected API | Requests go to |
   |---|---|---|
   | `https://opencode.ai/inference/go` | OpenAI Chat | `/inference/go/openai/v1/chat/completions` |
   | `https://opencode.ai/inference/go/v1/models` | OpenAI Chat | canonicalised to the chat endpoint |
   | `https://opencode.ai/inference/go/openai/v1/responses` | Responses | `/inference/go/openai/v1/responses` |
   | `https://opencode.ai/inference/go/anthropic/v1/messages` | Anthropic | `/inference/go/anthropic/v1/messages` |
   | `https://opencode.ai/inference/go/google/v1beta` + model id | Gemini | `…/models/<model>:streamGenerateContent?alt=sse` |

4. Paste your API key.
5. Pick a model (the OpenCode Go catalogue is built in) or type any model id.
6. Press **Save**. The app validates the endpoint and loads the live model list.

The default endpoint is `https://opencode.ai/inference/go`; all Go text models work through the OpenAI Chat endpoint because the gateway translates requests.

## Web tools

Web tools are local tool calls, not a server-side proxy:

- **`web_search`** uses DuckDuckGo HTML by default (no key required). If it gets rate-limited, add a Brave Search API key in Settings.
- **`web_fetch`** downloads a public page, strips scripts/navigation, and returns readable text. Redirects are followed manually and every hop is checked.
- Private, loopback, link-local, CGNAT and metadata addresses are blocked to prevent SSRF.
- Tool use is limited by **Max tool rounds** (0–8). The model gets the results and continues the answer; the UI shows each tool activity and the raw result is stored as a collapsible message.

Enable web tools per conversation with the globe button in the composer, or by default for new chats in Settings.

## Security and privacy

- The API key and optional Brave key are encrypted with AES-256-GCM using a non-exportable Android Keystore key.
- Requests go directly from the phone to the endpoint you configure. There is no OpenChat backend.
- Chat history is stored in a local Room database. Android backup and device transfer are disabled for app data.
- Keys are never written to logs. Error messages redact the token.
- Code and documents are only saved to Downloads when you explicitly tap save.

## Build

```bash
# JDK 21 and Android SDK 35 are required
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

The release build is minified with R8 and the APK is roughly 8 MB.

### Signed release APK (local)

`assembleRelease` always produces a signed `app-release.apk`. If no release keystore is
configured, it falls back to the Android debug key so the APK remains installable.

Create a keystore once (keep it and the passwords safe — losing it means you cannot update an
installed app):

```bash
keytool -genkeypair -v -keystore openchat.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias openchat
```

Create `keystore.properties` in the repository root (already gitignored):

```properties
storeFile=openchat.jks
storePassword=your-store-password
keyAlias=openchat
keyPassword=your-key-password
```

Or use environment variables instead:

```bash
export SIGNING_KEYSTORE_PATH=openchat.jks
export SIGNING_STORE_PASSWORD=your-store-password
export SIGNING_KEY_ALIAS=openchat
export SIGNING_KEY_PASSWORD=your-key-password
```

Then build and verify:

```bash
./gradlew clean assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

adb install -r app/build/outputs/apk/release/app-release.apk
```

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The JVM suite covers:

- all four streaming protocol parsers (text, reasoning, tool calls, usage, errors),
- request serialization including images and tool schemas,
- endpoint detection/canonicalisation,
- SSE framing,
- retry/backoff and `Retry-After`,
- Markdown parsing,
- model-list parsing,
- web-fetch SSRF guards and HTML extraction,
- DuckDuckGo parsing,
- token estimates,
- Room persistence, message ordering, cascade deletes and export,
- Compose rendering (Markdown, composer, settings) and an Activity launch smoke test
  via Robolectric.

`InferenceClientTest` uses MockWebServer to verify real HTTP headers — including `x-opencode-session` — and retry behaviour.

## GitHub Actions

### CI (`ci.yml`)

Runs on every push and pull request:

1. JDK 21 + Android SDK 35
2. `testDebugUnitTest`
3. `lintDebug`
4. `assembleDebug`
5. Uploads the debug APK and test reports.

### Release (`release.yml`)

Can be started in two ways:

- **Manually:** GitHub → **Actions** → **Release APK** → **Run workflow**, entering a version
  such as `1.0.0` (run it from `main`).
- **By tag:** push a tag such as `v1.0.0`; the tag must point at a commit that contains this
  workflow.

The workflow:

1. Runs the unit tests.
2. Builds a signed release if these repository secrets exist:
   - `ANDROID_KEYSTORE_BASE64` — base64 of the `.jks` file
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
3. Without secrets it signs the release with the standard Android **debug key**, so the APK
   installs, but later builds may require an uninstall if the CI runner generates a different
   debug key.
4. Attaches the APK to a GitHub Release and uploads a workflow artifact.
5. Verifies the APK signature with `apksigner` before uploading anything.

For stable, updatable releases, add the signing secrets below. Debug and release APKs use
different application ids, so both can be installed side by side.

Create a keystore once:

```bash
keytool -genkeypair -v \
  -keystore openchat-release.jks \
  -alias openchat \
  -keyalg RSA -keysize 4096 \
  -validity 10000

base64 -w0 openchat-release.jks > openchat-release.b64
# macOS: base64 -i openchat-release.jks -o openchat-release.b64
# paste the single-line contents of openchat-release.b64 into ANDROID_KEYSTORE_BASE64
```

Never commit the `.jks` or `.b64` file. Keep a backup of the keystore: Android only accepts
updates signed with the same key.

To publish by tag:

```bash
git push origin main
git tag v1.0.0
git push origin v1.0.0
```

### Why do I get “App not installed / package appears invalid”?

That happens when Android receives an unsigned APK, a corrupted download, or an APK signed
with a different key than the installed app.

- Use the APK from the latest **Release APK** workflow run after this fix. It is verified with
  `apksigner` before upload.
- If an earlier build of `dev.jonas.openchat` is already installed with a different signature,
  uninstall it once:
  ```bash
  adb uninstall dev.jonas.openchat
  ```
  Then install the new APK. You can also uninstall it from Android Settings → Apps.
- Make sure “Install unknown apps” is allowed for the app you use to open the APK.
- Do not use a file named `*-unsigned.apk`; the current pipeline no longer publishes one.
- For stable updates, configure the four signing secrets. Without them the CI debug key may
  change between runners and Android will reject an in-place update.

## Architecture

```
app/src/main/java/dev/jonas/openchat/
├── core/
│   ├── api/          endpoint resolver, 4 protocol adapters, model catalogue/parser
│   ├── model/        domain models and normalized stream events
│   ├── net/          SSE framing and retry policy
│   ├── text/         Markdown parser and token estimates
│   └── tool/         local web search/fetch and tool executor
├── network/          OkHttp streaming client + model list service
├── data/             Room, DataStore, Keystore, attachments, repository
├── di/               tiny manual dependency container (no Hilt startup cost)
└── ui/               Compose chat, drawer, model picker, settings, Markdown renderer
```

Streaming is normalized into `ProviderEvent`s, so the chat ViewModel is protocol-agnostic:

```
OpenAI Chat ─┐
Responses   ─┤
Anthropic   ─┼─> ProtocolAdapter ─> TextDelta / ReasoningDelta / ToolCall / Usage / Done
Gemini      ─┘
```

Performance choices:

- Compose state updates are throttled to ~25 fps while streaming instead of one update per token.
- Markdown parsing is debounced and runs off the main thread.
- Room writes happen once per assistant round, not per token.
- The SSE reader is incremental and handles split frames and multi-line data.
- No Hilt/RxJava/WebView; the release APK is R8-minified.

## Dependency updates

Dependabot is configured to keep pull-request noise low:

- routine Gradle **patch/minor** updates arrive as one grouped PR;
- GitHub Actions updates arrive as one grouped PR;
- major upgrades of the interdependent build toolchain (Gradle, AGP, Kotlin,
  Compose compiler/BOM, KSP) are ignored and should be done deliberately.

Merge a Dependabot PR only when its CI run is green. Do not batch major updates:
upgrade the toolchain locally, run the tests and build, then update the pins together.

## Known limitations

- OpenCode's free-tier chat models return `403` outside OpenCode; use paid/Go models.
- Vision and tool-calling support depends on the selected model. Models that do not accept images will return a provider error.
- Scanned PDFs without a text layer produce no text; OCR is not bundled.
- The app is a personal client, not a coding agent; it identifies itself with its own user agent and session header as requested by OpenCode.
- There is no cloud sync or multi-device history.

## License

No license file is included yet. Add one before distributing publicly.
