# Nook

A personal e-book reader for a 10 inch Android tablet. Kotlin, Jetpack Compose, minSdk 26.

Status: source code only. It has not been compiled yet, because the environment it was written in has no Android SDK and blocks the Google Maven repository. The first build may report compile errors that need small fixes. Send the error log and they can be fixed quickly.

## Features

Library
- Import EPUB and PDF (multi select). Duplicates are detected by file hash.
- Two pane layout on wide screens: category sidebar plus cover grid.
- Search, sort, reading status filters, categories, cover and metadata editing.

EPUB reader
- Continuous scroll, tap left or right edge to page, tap the middle to show or hide bars.
- Font size, line spacing, margins, font family, justify, light, sepia, dark and black themes.
- Table of contents, search in book, bookmarks, highlights in four colors, notes on highlights.
- Reading position saved by chapter and scroll position.

PDF reader
- Continuous scroll, optional two pages side by side in landscape, zoom, dark mode by color inversion.
- Bookmarks and reading position.

AI (your own API keys)
- Providers: OpenAI, Google Gemini, Anthropic Claude, DeepSeek, OpenRouter, Groq, and any OpenAI compatible endpoint.
- Each provider has its own adapter. Model list can be loaded from the provider. A connection test is included.
- Each task can use a different provider: chat, translation, explanation, categorizing, metadata.
- Select text in an EPUB, then use Translate, Explain or Ask AI from the selection menu. Translation goes Indonesian to English or anything else to Indonesian, and uses the surrounding paragraph to choose the right meaning.
- Book chat uses the text around your current reading position as context.
- Categorizing sorts books into shelves in batches of 20.
- Metadata and covers come from Google Books and Open Library. The AI does not browse the web. It only picks the best match among the search results.

Google Drive sync
- Login with Google. Uses only the drive.file scope, so the app sees only files it created.
- Creates a folder named Nook in Drive. Books are stored as `<hash>.epub` or `<hash>.pdf`, and `state.json` holds metadata, progress, bookmarks and highlights.
- Progress, bookmarks and highlights merge per item by timestamp. Books missing on a device show a cloud icon and download when opened.

## Known limits

- PDF text cannot be selected. For PDF, translation and explanation work on the whole current page through buttons in the AI panel. Scanned PDFs without a text layer cannot be translated.
- PDF has no highlights, only bookmarks.
- EPUB is rendered in a WebView in scroll mode. There is no paginated mode.
- Book chat sees only about 9000 characters around your position, not the whole book.
- Conflicts on the same item use the newest timestamp. Metadata, progress and annotations merge separately.
- API keys are stored in app private storage without extra encryption. Backup is disabled so keys do not go to Google backup.
- UI text is in Indonesian.

## Build the APK

Option A. GitHub Actions (no local setup)
1. Create a private repository and push this folder to it.
2. Open the Actions tab and run the workflow named Build APK, or just push to main.
3. When it finishes, download the artifact named nook-apk. Install the APK on the tablet.

Option B. Android Studio
1. Open this folder in Android Studio (Ladybug or newer, JDK 17).
2. Let it sync, then Build, Build Bundle(s) / APK(s), Build APK(s).

The APK is signed with `app/nook.keystore` (password `nookreader`). Keep the same keystore for every build so the SHA-1 stays the same and Google login keeps working. For a private personal app this is acceptable. Do not publish this keystore if you ever distribute the app.

## Set up Google login

Google login needs an OAuth client that belongs to you. Do this once.
1. Go to console.cloud.google.com and create a project.
2. Enable the Google Drive API.
3. Open OAuth consent screen, choose External, and add your Google address as a Test user.
4. Open Credentials, Create credentials, OAuth client ID, type Android.
5. Package name: `com.nook.reader`. SHA-1: shown in the app under Settings, Google Drive, setup guide. Or run `keytool -list -v -keystore app/nook.keystore -storepass nookreader` and copy the SHA1 line.
6. In the app, Settings, Login with Google.

If login expires every 7 days, set the publishing status of the consent screen to In production. Error code 10 means the package name or SHA-1 does not match the OAuth client.

## Project layout

```
app/src/main/java/com/nook/reader/
  ai/        AiClient (3 API adapters), AiService (prompts)
  book/      Epub (parser), PdfBook (renderer and text)
  data/      Models, Settings, Library (storage, import, merge)
  meta/      Google Books and Open Library lookup
  sync/      DriveSync (REST client), SyncManager
  ui/        Compose screens, EPUB WebView reader, PDF reader
app/src/main/assets/nook.js   selection, highlight and scroll logic inside the EPUB WebView
```
