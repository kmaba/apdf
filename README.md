# apdf

A tiny, fully-offline Android app that converts and merges documents into a single PDF.

- **100% on-device** — no network, no accounts, no tracking
- Converts **Word (.docx)**, **PowerPoint (.pptx)**, **images** (jpg/png/webp/gif/bmp), **text** and **PDFs**
- **Merges** any mix of files into one PDF, in your chosen order
- **Always under 8 MB** — output is automatically re-encoded to stay small
- **Save As** — you pick exactly where the PDF is written
- **Share menu** — select files in any app → Share → apdf
- Minimal black UI, Space Grotesk typeface

Package: `link.kmaba.apdf` · minSdk 21 · targetSdk 34

## Build

```sh
./gradlew :app:assembleRelease
```

The project builds without any signing config (F-Droid signs its own builds). If a
`keystore/release.jks` exists next to the project it is used to sign the release APK.

## License

GPL-3.0 — see [LICENSE](LICENSE). Bundled third-party JavaScript assets retain
their own licenses, see [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md).
