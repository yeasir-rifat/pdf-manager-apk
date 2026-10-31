# Office → PDF — যাচাই (M6)

## কী যাচাই হয়েছে, কী হয়নি
- **হয়েছে:** সব নতুন ফাইল লাইন ধরে পড়ে মিলিয়ে দেখা (সিগনেচার, স্মার্ট-কাস্ট, ব্র্যাকেট, ইমপোর্ট); বিদ্যমান ছাঁচের সঙ্গে তুলনা।
- **হয়নি:** কম্পাইল, ইউনিট টেস্ট চালানো, R8, আসল ডিভাইস, আসল Google অ্যাকাউন্ট। লেখার পরিবেশে Kotlin কম্পাইলার, Android SDK ও নেটওয়ার্ক ছিল না।
- তাই এই কোড **CI-র প্রথম সবুজ রান না পাওয়া পর্যন্ত অপরীক্ষিত** ধরতে হবে।

## ১. CI-র প্রথম রান
`build-apk.yml` APK আগে বানায়, টেস্ট পরে চালায় (`if: always()`), তাই কম্পাইল ভাঙলে প্রথম লাল ধাপ **Build debug APK**।
- কম্পাইল ত্রুটি এলে লগের `e: file:///…/<ফাইল>.kt:<লাইন>` লাইনগুলো পাঠান; সাধারণত ছোট সংশোধন।
- টেস্ট নতুন যোগ হয়েছে ৫৪টি: `DriveClientTest` ২৭, `DriveJsonTest` ১০, `DriveTempJournalTest` ১০, `DriveLeftoversTest` ৮ (এবং `OfficeHelpersTest`-এ ১, `ToolCatalogTest` ২৪→২৭ টুল)। আগের টেস্ট বদলায়নি।
- স্থানীয়ভাবে: `gradle :app:testDebugUnitTest`।

## ২. কম্পাইলে সবচেয়ে সম্ভাব্য সমস্যা (যেখানে আগে তাকাবেন)
| জায়গা | কেন |
|---|---|
| `GoogleDriveAuth.tokenBlocking` | `com.google.android.gms.tasks.Tasks` ক্লাসপাথে না এলে `play-services-tasks` যোগ করতে হবে |
| `GoogleDriveAuth` (M2) | `AuthorizationRequest`/`AuthorizationResult` API-র নাম `play-services-auth:21.2.0`-এ মিলছে কি |
| `DriveClient.call` | `val failure = try { return … } catch …` গঠন |
| `FakeDriveServer` (test) | `com.sun.net.httpserver` Gradle-এর JDK-তে আছে কি (JDK 17 তে থাকে) |
| `AppIcons` নতুন ৩টি পাথ | কম্পাইল হবে, কিন্তু চেহারা ডিভাইসে দেখতে হবে |

## ৩. R8 (release)
`proguard-rules.pro`-তে `com.pdfmanager.app.data.**` আগে থেকেই keep, তাই নতুন ক্লাসের জন্য নিয়ম যোগ হয়নি। Google Play services নিজের consumer নিয়ম নিয়ে আসে। CI একই রানে R8 ও `-PnoR8` দুটোই বানায়; R8 বিল্ডে Google অনুমতি বা কনভার্ট ভাঙলে `PdfManager-release-safe-apk` দিয়ে তুলনা করুন।

## ৪. ডিভাইস পরীক্ষার ক্রম
**মনে রাখুন:** CI-র debug APK-তে Google অনুমতি কাজ করবে না (প্রতি রানে নতুন debug key)। পরীক্ষা করতে হবে **স্থায়ী keystore-এ সই করা release APK** দিয়ে, এবং Google Cloud-এ ওই keystore-এর SHA-1 দিয়ে Android OAuth client থাকতে হবে (`README-OFFICE-TO-PDF.md` §৬)।
1. O9, O10, O11, O12 (UI ও অনুমতি) → 2. O1, O2 (একটি সাধারণ DOCX, Drive খালি) → 3. O6, O7, O13 → 4. O3, O4, O18 (ব্যর্থতা ও পুনরুদ্ধার) → 5. O5, O19 → 6. O16, O17, O20 → 7. বাংলা DOCX/PPTX, চওড়া/বহু-শিট XLSX (M0-র মতো গুণমান) → 8. Android 8/9 (O8) ও 13+।
