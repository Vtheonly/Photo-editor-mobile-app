# Android Recovery Scanner

A no-root Android media recovery scanner focused on **content rather than filenames**.

## Scan modes

### Normal scan
Reads accessible files selected through Android's Storage Access Framework and checks their headers.

### Deep scan
Reads the **entire byte stream** of every accessible file and searches for media signatures anywhere inside the file. This can find:

- renamed images such as `photo.bin`
- files with missing/wrong extensions
- JPEG/PNG data embedded inside another accessible file
- media appended to otherwise unrelated files
- MP4/MOV/3GP/HEIC ISO-BMFF headers at non-zero offsets
- GIF/BMP/WebP signatures that are not reflected by the filename

Deep scanning uses a rolling overlap between chunks so signatures crossing a 1 MiB read boundary are not missed.

## Recovery

Recovered files are written to a **user-selected destination**, not back into the scanned source. Embedded JPEG and PNG candidates are carved from their detected offset using their format terminators.

## MediaStore

The scanner can inspect Android's indexed media collection and reads both image and video permissions on Android 13+.

## What this app deliberately does not claim

This is not raw flash-storage forensic recovery. An ordinary unrooted Android application cannot read the physical UFS/eMMC block device or arbitrary deleted blocks. Android storage encryption, TRIM/garbage collection, filesystem state, and device permissions can make deleted data unavailable even when the user remembers it existed.

The app therefore searches **everything it is actually allowed to read**, as deeply as practical, rather than pretending that a normal app can bypass Android's storage security model.

## Important recovery practice

Do not save recovered files into the same source location. Writing new data can overwrite recoverable content. For important evidence, stop using the device and use a proper forensic acquisition workflow instead.
