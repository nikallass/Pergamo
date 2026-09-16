<p align="center">
  <img src="store_assets/app_icon_512.png" width="120" height="120" alt="Pergamo">
</p>

<h1 align="center">Pergamo</h1>

<p align="center">
  <strong>A privacy-first, offline PDF utility for Android</strong>
</p>

<p align="center">
  <a href="https://github.com/nikallass/Pergamo/releases/latest">
    <img src="https://img.shields.io/github/v/release/nikallass/Pergamo?include_prereleases&label=Download%20APK&color=green&logo=android" alt="Download APK">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License">
  </a>
  <a href="https://github.com/nikallass/Pergamo/issues">
    <img src="https://img.shields.io/github/issues/nikallass/Pergamo?style=flat&color=red" alt="GitHub Issues">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android" alt="Platform">
  <img src="https://img.shields.io/github/last-commit/nikallass/Pergamo?style=flat&color=orange" alt="Last Commit">
</p>

---

## 📱 Get it on Android

<p align="center">
  <a href="https://github.com/nikallass/Pergamo/releases/latest">
    <img src="https://img.shields.io/badge/Download%20APK-GitHub%20Releases-3DDC84?style=for-the-badge&logo=android&logoColor=white" height="40" alt="Download APK">
  </a>
</p>

1. Open the [latest release](https://github.com/nikallass/Pergamo/releases/latest) and download the `.apk`.
2. Allow installs from your browser or file manager when Android asks.
3. Open the file and tap **Install**. To update, install the newer APK on top.

The APK is the `opensource` flavor: Tesseract OCR, no Google Play Services, no network permission.
It is signed with this repository's own key, so it installs alongside, not over, the store version.

> Offline · Privacy-first · No account required

---

## 🔱 What Pergamo adds

Pergamo is a fork of the original PDF Toolkit. It adds the scanning and navigation work below.

**Scanner filter, rewritten** — the old colour mode produced a plain photo: grey shadows, ink that
stayed dark grey. `ScanEnhancer` now estimates the paper colour across the page (a coarse
illumination field decides what is paper, and that colour is diffused into text blocks, stamps and
photos so they are not mistaken for background), divides it out, and applies a levels curve on
luminance while scaling R/G/B together, so paper turns white and coloured ink keeps its hue. Near-grey
pixels are forced neutral, so black text no longer drifts towards the paper's tint.

**Two sliders instead of five preset modes** — *Background whitening* (0 % = the photo as shot,
100 % = clean scan) and *Black point* (where ink turns black; on an unwhitened photo it is plain
contrast), plus a *Black & white* switch. Defaults: whitening 100 %, black point 50 %.

**Live preview** — see the page on the target sheet before generating: pinch to zoom, swipe between
pages, a compare button for the untouched photo, and a progress hairline while the filter catches up.
Settings apply to the page on screen, or to every page with *Apply to all pages*.

**Scan and Images-to-PDF merged** — one tool builds a PDF from camera captures and gallery photos
alike, with page reordering, an editable file name, a choice between the app folder and a
system dialog, and a share button for the finished file. The camera screen has a gallery shortcut.

**Home screen** — full-width rows instead of square tiles that truncated every name, regrouped by
what the user is trying to get done, plus favorites, recently used and a collapsed *Hidden* section;
long-press a row to pin or hide it.

**Page orientation** — a landscape photo now gets a landscape sheet instead of white bands.

**History** — tap an entry to open its file; entries are titled by the saved file name.

---
## ✨ Features

### 📄 PDF Management
- **Merge PDF & Images** — Combine multiple PDF files and images (JPG, PNG, WebP) into a single document with File & Page preview modes
- **Split PDF** — Split into multiple files or specific page ranges
- **Compress PDF** — Reduce file size while maintaining quality
- **Reorder Pages** — Visual drag-and-drop page reordering
- **Rotate Pages** — Rotate specific pages or entire documents
- **Extract Pages** — Extract specific pages to create new PDFs
- **Delete Pages** — Remove unwanted pages

### 🔄 Conversion Tools
- **Images to PDF** — Create PDFs from gallery images
- **PDF to Images** — Convert PDF pages to high-quality images (JPG/PNG/WebP)
- **HTML to PDF** — Direct webpage to PDF conversion
- **Scan to PDF** — Camera-based document scanning with automatic edge detection

### ✏️ Editing & Annotation
- **Annotate** — Highlight, draw, and markup PDFs with custom Canvas layering
- **Sign PDF** — Add digital signatures to documents
- **Fill Forms** — Complete PDF forms on the go
- **Flatten PDF** — Make forms and annotations permanent

### 🔒 Privacy & Security
- **Lock PDF** — Password-protect your files
- **Unlock PDF** — Remove passwords (with valid password)
- **Watermark** — Add text or image watermarks
- **All processing on-device** — No cloud, no servers
- **No internet permission** — Completely offline capable
- **No data collection or tracking**

### 🔤 OCR & Text
- **Extract Text** — Pull text content from PDF pages
- **ML Kit OCR** — Play Store flavor (on-device, smaller APK)
- **Tesseract OCR** — F-Droid and opensource flavors (100% open source)

### 🖼️ Image Tools
- **Compress Images** — Optimize file sizes
- **Resize Images** — Change dimensions
- **Format Conversion** — JPG, PNG, WebP
- **Remove Metadata** — Strip EXIF data for privacy

---

## 🏗️ Build Flavors

| Flavor | OCR Engine | Ads | Firebase | Distribution |
|--------|-----------|-----|----------|--------------|
| `playstore` | ML Kit | No | No | Google Play |
| `fdroid` | Tesseract | No | No | F-Droid |
| `opensource` | Tesseract | No | No | GitHub Releases |

All flavors are **privacy-first** with no ads, no analytics, and no proprietary dependencies except ML Kit in the Play Store flavor.

---

## 🛠️ Tech Stack

| Category | Technology |
|----------|------------|
| **Language** | Kotlin 100% |
| **UI Framework** | Jetpack Compose (Material Design 3) |
| **Architecture** | MVVM + Clean Architecture |
| **PDF Processing** | PdfBox-Android, Android PdfRenderer |
| **Annotations** | Custom Canvas + BlendMode layering |
| **OCR (Play Store)** | Google ML Kit |
| **OCR (F-Droid / Opensource)** | Tesseract (tesseract4android) |
| **Camera** | CameraX |
| **Images** | Coil, Glide, uCrop |
| **Database** | Room |
| **Preferences** | DataStore |
| **Async** | Coroutines & Flow |
| **Build** | Gradle + KSP |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17+
- Android SDK 26+

### Build

```bash
# Clone the repository
git clone https://github.com/nikallass/Pergamo.git
cd Pergamo

# Play Store flavor (ML Kit OCR)
./gradlew assemblePlaystoreRelease

# F-Droid flavor (Tesseract OCR, no proprietary deps)
./gradlew assembleFdroidRelease

# Opensource flavor (fully FOSS)
./gradlew assembleOpensourceRelease
```

---

## 📦 Download

| Platform | Link | Notes |
|----------|------|-------|
| GitHub Releases | [Download APK](https://github.com/nikallass/Pergamo/releases/latest) | Opensource flavor, manual install |

Releases are built and signed by [GitHub Actions](https://github.com/nikallass/Pergamo/actions/workflows/release-apk.yml) from the tagged commit.

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request against `master`

**Important:** The F-Droid and opensource flavors must remain free of proprietary dependencies. Any new dependencies must be compatible with the F-Droid inclusion policy.

See [open issues](https://github.com/nikallass/Pergamo/issues) for feature requests and bug reports.

---

## 👤 Maintainer

This build: [@nikallass](https://github.com/nikallass)  
Original PDF Toolkit: Narisetti Chaitanya Naidu

---

## 📄 License

Copyright © 2026 Narisetti Chaitanya Naidu

Licensed under the Apache License, Version 2.0  
See [LICENSE](LICENSE) for full text.
