# Recovery Scanner

An Android no-root media recovery assistant that inspects storage the operating system allows the app to read.

## What this version does

- Recursively scans a user-selected folder through the Storage Access Framework.
- Scans Android MediaStore when media permissions are granted.
- Ignores filename extensions when identifying content.
- Detects JPEG, PNG, GIF, WebP, BMP, HEIC and common ISO-BMFF video containers.
- Flags extension/content mismatches such as `photo.bin` containing JPEG bytes.
- Detects JPEG/PNG signatures embedded inside accessible files during the initial content window.
- Lets the user select candidates and writes recovered copies to a separately chosen destination.
- Performs all scanning and copying off the main thread and supports coroutine cancellation.

## Important limitation

This is **Version A: no root**. A normal Android application cannot read the raw blocks of internal UFS/eMMC storage, deleted directory entries, filesystem slack, or TRIMmed/garbage-collected blocks. Therefore this application cannot perform the same physical deleted-block carving as PhotoRec against a raw disk image.

The app deliberately does not claim to recover data that Android does not expose. On an unrooted phone, its deepest useful scope is accessible files, MediaStore records, and content inside files the user explicitly grants through SAF.

## Recovery safety

Recovered files are written to a user-selected destination rather than overwriting the source. For important recovery attempts, avoid installing large applications, recording new media, factory-resetting, flashing firmware, unlocking the bootloader, or otherwise writing substantial new data to the device.

## Project layout

```text
app/src/main/java/com/vtheonly/recoveryscanner/
├── MainActivity.kt
└── scanner/
    ├── MediaType.kt
    ├── SignatureRegistry.kt
    ├── StorageScanner.kt
    └── RecoveryWriter.kt
```

## Build

The repository includes a GitHub Actions debug-build workflow. Locally, use Gradle 8.9 with JDK 17 and run `gradle :app:assembleDebug`.

## Future extensions

The architecture can later add stronger container validation, JPEG/PNG boundary validation, EXIF recovery, thumbnail discovery, MediaStore-vs-filesystem discrepancy reports, resumable scans, previews, duplicate detection, and optional privileged/forensic acquisition as a separate capability. Those additions must not be represented as raw deleted-storage recovery when Android does not expose the underlying blocks.
