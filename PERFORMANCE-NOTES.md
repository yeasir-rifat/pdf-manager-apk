# Performance changes (v0.3 -> v0.3 fast)

1. Release build: R8 code + resource shrinking is ON (rules in app/proguard-rules.pro). CI builds three APKs: debug, `PdfManager-release-apk` (R8) and `PdfManager-release-safe-apk` (no R8, fallback). Test OCR, Compress, Merge, Print on the R8 one first.
2. Library scan: `PdfDao.sync` reads stored rows once and only writes new or changed files (before: one UPDATE per file).
3. Thumbnails: disk cache is trimmed every 25 saves instead of every save; 3 renders in parallel instead of 2.
4. Viewer: pages just below/above the screen are rendered in the background (not while zoomed in).
5. Library: selected-file lookup uses a hash set.
6. gradle.properties: parallel build + build cache (faster CI/builds only).

Test after installing: see TEST-CHECKLIST.md, plus open a 100+ page PDF, scroll fast, rescan the library, run OCR/compress/merge once each with the release APK.

## Size reduction
7. OCR was removed (screen, route, OcrEngine, Tesseract library and the INTERNET permission). That removes the native libraries, so the app has no CPU-specific code and runs on every phone type.
8. material-icons-extended was removed. The 54 icons the app uses live in ui/AppIcons.kt (about 10 MB smaller). Screens use `AppIcons.Name` instead of `Icons.Rounded.Name`.

## Compress screen
9. The Compress screen now has a 0-100% strength slider (plus Light/Medium/Strong/Maximum presets) and shows an estimated result size that updates as the slider moves. The estimate is measured by trial-encoding the 3 biggest images of the picked PDF at 5 strengths (PdfCompressor.analyze); the real size is shown after compressing. Text-only PDFs are reported as barely compressible.

## Sharpness fix (blurry pages, zoom and thumbnails)
10. Pages are now rendered at exactly the pixel size they are drawn at (width from layout constraints, height from the same formula), so nothing is resampled.
11. Zoom bug: the page state kept its old bitmap when the zoom changed, so zoomed pages were never re-rendered. Fixed.
12. Zoom: the on-screen part of each page is rendered at real screen resolution (PdfRenderer matrix), so 5x zoom is as sharp as 1x. The old 2000px whole-page cap is gone.
13. Thumbnails (library, recents, grid, page picker) are rendered at their measured box size; disk cache JPEG quality 82 -> 92.

## Tool screens: filling the empty space
14. New ui/ToolExtras.kt: Recent PDFs strip (newest library files, one tap to pick), Recent results list (earlier outputs of the tool with Open and Share, read from Documents/PDF Manager), live sheet preview for Pages/sheet, front/back page preview and sheet count for Booklet.
15. Shown on: Merge (recent PDFs + results), Pages/sheet (preview + recent PDFs + results), Booklet (preview + recent PDFs + results), Scan to PDF (results).
16. New DAO query observeNewest and OutputStore.recentPdfs (MediaStore query). Test on Android 9 and on 10+ because the query differs.
