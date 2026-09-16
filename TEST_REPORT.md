# Recovery Scanner — Test Program Report

**Target:** `com.vtheonly.recoveryscanner` @ commit `1e38ca3` (base `92b3a8d`)
**Suite:** 104 JVM tests (Robolectric 4.14.1, SDK 34, NATIVE graphics where decode matters)
**Scope of this report:** automated coverage of Gates 1, 2 and 4 of the agreed test program.
Gates 3 (real devices) and 5 (recovery proof on-device) are checklist items and are not
executable on a JVM — see "Device-only remainder".

---

## 1. Verdict per gate

| Gate | Requirement | Status |
|------|-------------|--------|
| 1 — Basic correctness | signatures, extension mismatch, structural validation, exact offsets, exact lengths, recovery writer | **PARTIAL — 4 build blockers fixed to make it compile; 14 engine defects documented (D-01…D-19 subset)** |
| 2 — Samsung-specific | thumbdata, trash, SEFT/SEFH, Motion Photo, DualShot, Notes, Smart Switch, caches | **PARTIAL — core SEF/thumbdata/archive paths work and are byte-exact where the directory record is intact; 5 Samsung defects (D-13, D-15, D-16, D-17 + MediaStore query D-14)** |
| 3 — Real devices | actual Samsung hardware | **NOT EXECUTABLE on JVM — checklist provided** |
| 4 — Adversarial data | malformed containers, truncation, false signatures, corrupted SEF, malicious archives, overflow | **PASS with documented exceptions — crash-safety holds for malformed *content* (D-19 is the file-lifecycle hole); false-positive flood and 2 false-VALID classes documented** |
| 5 — Recovery proof | SHA-256 ground truth loops | **AUTOMATED HALF DONE — every writer test compares SHA-256 against fixture manifest; the delete→scan→recover loop requires a device** |

The one-line summary: after four build fixes, the engine is testable and its **deep scan +
Samsung SEF directory-record path + archive extraction + writer are genuinely correct and
byte-exact** — but a dozen real defects (several of them recovery-killing: D-04, D-07,
D-08, D-12, D-17, D-19) stand between this code and the "serious recovery engine" bar.

---

## 2. How to run

```bash
./gradlew testDebugUnitTest          # 104 tests, ~40 s on a laptop
```

Test reports: `app/build/test-results/testDebugUnitTest/` (XML) and
`app/build/reports/tests/testDebugUnitTest/` (HTML).
CI runs the same command on every push (`.github/workflows/ci.yml`).

Fixtures are committed under `app/src/test/resources/fixtures/` with a SHA-256 manifest
(`fixtures.json`, ground truth for byte-exact comparisons). They are reproducible via
`python3 tools/gen_fixtures.py` (Pillow + ffmpeg required).

---

## 3. Test inventory

| Class | Tests | Covers plan sections |
|-------|-------:|----------------------|
| `SpikeEnvironmentTest` | 6 | environment proofs (file:// streams, SAF stub, EXIF, NATIVE decode) |
| `Gate1DetectionTest` | 14 | 3, 4, 5, 28 (D-07 dead-end) |
| `Gate1CarvingTest` | 35 | 6, 7 (boundaries), 8–14, 15, 16 |
| `Gate1RecoveryWriterTest` | 7 | 28 |
| `Gate2SamsungTest` | 28 | 7, 17–24, 26, 41 (zip traversal) |
| `Gate4AdversarialTest` | 14 | 27, 31, 40, 41 |

---

## 4. Traceability matrix (all 44 sections)

| # | Section | Coverage |
|---|---------|----------|
| 1 | Build/installation | **F-00…F-03 fixed** (repo did not compile at HEAD). APK builds locally; device install matrix = device checklist |
| 2 | Permissions | Device-only (Robolectric cannot grant runtime media permissions meaningfully) |
| 3 | SAF / selected folders | Automated: empty folder, nested traversal, `fromTreeUri(file://)` throws; persistent-permission lifecycle = device |
| 4 | Basic detection | **Automated** — full matrix over 27 fixtures incl. HEIC brands |
| 5 | Extension mismatch | **Automated** — content wins, flag, original extension preserved |
| 6 | JPEG structural | **Automated** — valid variants, truncation, corruption, multi-carve, boundaries |
| 7 | .thumbdata | **Automated** — exact carve, 10 KB floor, decode gate, 1 MiB boundary, duplicates; real 1–4 GB thumbdata perf = device (R-03) |
| 8 | PNG | **Automated** — IEND stop, missing IEND, absurd IDAT, CRC tolerance (D-05) |
| 9 | GIF | **Automated** — off-by-one defect D-12 documented |
| 10 | WebP | **Automated** — RIFF length respect; endianness defect D-04 documented |
| 11 | HEIC/HEIF | **Automated** — brands mapped; never-VALID limitation D-02 |
| 12 | MP4/MOV/3GP | **Automated** — moov-last exact, faststart truncation D-01, 64-bit/size-0/absurd/missing-moov |
| 13 | MKV/WebM | **Automated** — no carve path D-08; fake EBML handling |
| 14 | Generic binary carving | **Automated** — multi-format blob with exact offsets |
| 15 | .nomedia | **Automated** (deep scan visits all) |
| 16 | Hidden directories | **Automated** |
| 17 | Samsung trash names | **Automated** — EXIF restoration incl. 15–20 digit matrix; D-15 double extension |
| 18 | Motion Photo | **Automated (synthetic)** — exact offset/length, byte-identical payload; real Galaxy samples = device |
| 19 | Corrupted SEF | **Automated** — 3 fallback paths survive; D-16 SEFT-byte inclusion |
| 20 | DualShot_ExtraImage | **Automated** (synthetic) |
| 21 | DualShot_DepthMap | **Automated** — conservative non-media handling |
| 22 | Samsung Notes | **Automated** — sdocx/zip extraction, original bytes |
| 23 | Smart Switch | **Automated** — real-zip extraction, non-zip refusal |
| 24 | Cache recovery | **Automated** — extensionless content detection |
| 25 | Shizuku | Device-only (binder); R-02 process-spawn finding from review |
| 26 | Duplicate detection | **Automated doc** — no content dedup exists (D-13) |
| 27 | False positives | **Automated** — naked signatures never VALID; D-06/D-18 false-VALID classes documented |
| 28 | RecoveryWriter | **Automated** — SHA-256 identity, offsets/lengths, collisions, refusal semantics |
| 29 | EXIF | Partially automated (dates drive D-15); GPS/orientation/thumbnail = device + real samples |
| 30 | Video preview | Not executable on JVM (MediaMetadataRetriever shadow); device |
| 31 | Large files | 32 MB stress automated with exact results + time bound; 1–4 GB = device |
| 32 | Low storage | Device-only |
| 33 | Interrupted scans | D-19 (vanished file kills scan) automated; Home/rotate/kill = device |
| 34 | SD card | Device-only |
| 35 | Android matrix | Device-only |
| 36 | Samsung device matrix | Device-only |
| 37 | Real deleted-file tests | Device-only (Gates 3/5) |
| 38 | Ground-truth comparison | **Automated half** — SHA-256 vs manifest in every writer test |
| 39 | Performance benchmarks | Partial (32 MB stress); full benchmarks = device |
| 40 | Crash testing | **Automated** — malformed families never crash scanners; D-19 documented |
| 41 | Security | **Automated** — zip traversal containment, no recursive zip bombs, stream-extracted bomb, SEF overflow bounds, 64-bit clamp |
| 42 | UI tests | Not automated (programmatic UI; Espresso = future work) |
| 43 | Cancellation | **No Cancel control exists in the app** (R-04); nothing to test |
| 44 | Regression suite | **This suite is it** — runs in CI on every push |

---

## 5. Build blockers found at HEAD (all fixed in commit `218f6c9`)

| ID | Finding | Fix |
|----|---------|-----|
| F-00 | `minSdk 23` conflicts with Shizuku api 13.1.5 (needs 24) — manifest merger failure | `minSdk = 24` |
| F-01 | `StorageScanner.kt` did not compile: `suspend(ScannedProgress)` tokenization and `List<ScanResult>=withContext` (the `>=` is lexed as one token) | spacing fix |
| F-02 | AGP 8 disables AIDL by default → `IShizukuScanner` never generated → two classes had unresolved references | `buildFeatures { aidl = true }` |
| F-03 | `?.use{input->{…}}` double-brace lambdas: the inner lambda is *constructed and discarded* — the detection body never ran, so **scanTree/scanMediaStore always returned zero results** | single-brace rewrite |

---

## 6. Engine defect catalog

Confirmed by tests unless marked *code review*. None are fixed (per engagement scope);
each is pinned by a `doc` test that asserts current behavior so regressions are visible.

### Recovery-killing

| ID | Defect | Evidence |
|----|--------|----------|
| D-04 | `MediaCarver.webp` reads the little-endian RIFF size as big-endian. Every *real* WebP gets a garbage multi-GB "VALID" length, and `RecoveryWriter` then refuses it (`copied != recoveredLength`) → **real WebP files can never be recovered** | `Gate1CarvingTest.section10 doc - real WebP carve length…` |
| D-07 | Normal-scan results default to `SIGNATURE_ONLY` (no quality/length ever set) → checkboxes disabled, writer refuses → **the normal scan can list files but never recover any** | `Gate1DetectionTest.section28 doc` |
| D-08 | No EBML carve path: MKV/WebM are `SIGNATURE_ONLY` dead ends; deep scan does not even carry an EBML signature, so MKV/WebM are invisible to the deepest scan | `Gate1CarvingTest.section13 doc` |
| D-12 | `MediaCarver.gif` consumes one extra byte before image sub-blocks → GIFs never carve byte-exact (wrong lengths / PARTIAL / rejection) | `Gate1CarvingTest.section9 doc` |
| D-17 | `SamsungArtifactScanner.parseIsoBmff` reads the SEF trailer as a box and returns null → the tail scanner finds **nothing** for the Motion Photo layout it exists for (and the class is unreferenced by the UI) | `Gate2SamsungTest.section18b doc` |
| D-19 | `MediaCarver.analyze` leaks `FileNotFoundException`; no scanner wraps per-file work in try/catch → **one file deleted mid-scan aborts the entire scan** (Sections 1/33) | `Gate4AdversarialTest.section40 doc` |

### Correctness

| ID | Defect | Evidence |
|----|--------|----------|
| D-01 | Faststart MP4 (`ftyp,moov,mdat` — the common web layout) carves only to moov end, loses mdat, still graded VALID | `Gate1CarvingTest.section12 doc` |
| D-02 | HEIC/HEIF can never reach VALID (VALID requires `moov`; HEIC has `meta`/`mdat`) | `Gate1CarvingTest.section11 doc` |
| D-03 | WebP declared size is trusted without an EOF check → VALID beyond EOF | `Gate1CarvingTest.section10 doc - corrupted RIFF size` |
| D-05 | PNG chunk CRCs are skipped, never validated → corrupt PNGs grade VALID | `Gate1CarvingTest.section8` |
| D-15 | `restoreName` appends the extension twice (`...678.jpg.jpg`) | `Gate2SamsungTest.section17 doc` |
| D-16 | SEF string-scan fallback sets payload end at the SEFH marker, so recovered payloads carry the 4 `SEFT` trailer bytes | `Gate2SamsungTest.section19/20/21 doc` |

### False positives / volume

| ID | Defect | Evidence |
|----|--------|----------|
| D-06 | 2-byte `BM` signature: any file starting "BM" is a header-level BMP; deep scan floods with junk candidates, and crafted headers grade VALID with ~2 GB lengths | `Gate1DetectionTest`, `Gate4AdversarialTest.section27 doc` |
| D-18 | A planted 12-byte `RIFF….WEBP` header in noise passes both the deep-scan check and the carve → false VALID | `Gate4AdversarialTest.section27 doc` |
| D-13 | No content-based duplicate detection: identical bytes at N locations → N recoverable results | `Gate2SamsungTest.section26 doc` |

### Thresholds (documented behavior, may be intentional)

| ID | Behavior | Evidence |
|----|----------|----------|
| D-10 | `.thumbdata` carve drops JPEGs < 10 KB (many real thumbnails are smaller) | `Gate2SamsungTest.section7` |
| D-11 | `.thumbdata` carve aborts a JPEG candidate above 16 MB (*code review*, not exercised) | — |

### Code-review findings (not reachable from JVM tests)

| ID | Finding |
|----|---------|
| D-14 | `MediaStoreTrashScanner` queries `MATCH_TRASHED=MATCH_ONLY` **and** `MATCH_PENDING=MATCH_ONLY` — the intersection is almost always empty, so the trash query returns ~nothing |
| D-20 | `ShizukuCacheUserService` spawns one `sh -c cp` process per file (up to 1 000 sequential process spawns) |
| R-03 | `carveJpegs` is byte-by-byte and decodes every candidate unsampled — on real 1–4 GB `.thumbdata` files this is a freeze/OOM risk (the user-facing perf concern) |
| R-04 | There is no Cancel control anywhere in the UI (Section 43 requires one before production) |

---

## 7. What the engine gets right (worth keeping)

- **Deep-scan chunking is correct at the 1 MiB boundary** — SOI at bytes 1 048 575/576/577 and EOI straddling the boundary all carve byte-exact.
- **The SEFH directory-record path is byte-exact** for Motion Photo payloads (offset, length, SHA-identity) and survives all three corruption fallbacks tested.
- **Archive extraction is safe and correct**: media-only filter, temp-file materialization (path traversal structurally impossible), no recursive zip bombs, streams large entries to disk.
- **The writer never destroys existing files** (unique-name suffixing) and produces SHA-256-identical output whenever the carve length is right.
- **Malformed content of every tested family never crashes a scanner** — the crash surface is file lifecycle (D-19), not bytes.

---

## 8. Device-only remainder (Gates 3 & 5 checklist)

1. Install/launch/upgrade matrix on Android 11–15 One UI 3–7 (Section 1, 35).
2. Runtime media permissions, All Files Access lifecycle, Shizuku grant/revoke (Sections 2, 25).
3. Real Samsung artifacts: Motion Photos across S/Note/A/Z generations, real `.thumbdata` (1–4 GB), Gallery trash after empty, `Android/.Trash/com.sec.android.gallery3d`, SD-card variants (Sections 7, 17–21, 34, 36, 37).
4. Delete → scan → recover → SHA-256 compare loops (Gate 5).
5. Performance/battery/RAM capture per scan mode (Section 39), including the R-03 thumbdata risk on real multi-GB files.
6. Cancellation/leak checks once a Cancel control exists (Section 43, R-04).

---

## 9. Environment notes for future contributors

- Tests run under Robolectric SDK 34 with `@GraphicsMode(NATIVE)` where `BitmapFactory` gates matter; in LEGACY mode the shadow bitmap would accept garbage and the thumbdata decode-gate tests would be meaningless.
- The SAF stub (`support/StubDocumentsProvider`) serves real files through the **actual** `TreeDocumentFile` code path; URI routing was verified against the SDK 34 `DocumentsContract` bytecode (`tree/<t>/document/<p>/children`; `createDocument` via `resolver.call("android:createDocument")`).
- Under Robolectric instrumentation, `FileInputStream.skip(n)` may over-report past EOF; all engine paths tested go through `BufferedInputStream`, which clamps — but any future raw-stream skip logic should be aware of it.
