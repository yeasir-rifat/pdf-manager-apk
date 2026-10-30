# Print ফিচারের test ও acceptance (ধাপ ১২)

**অবস্থা:** ফিচার "সম্পূর্ণ" বলা যাবে না যতক্ষণ নিচের "ডিভাইসে হাতে পরীক্ষা" অংশ পূরণ না হয়। Unit test শুধু যুক্তি যাচাই করে; printer, print service বা Android সিস্টেমের আচরণ যাচাই করে না।

## ১. কমান্ড (নিজের মেশিনে)
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
gradle :app:lintDebug
```
(প্রজেক্টে Gradle wrapper নেই: Gradle 8.9 ইনস্টল করে `gradle` চালান, অথবা GitHub Actions ব্যবহার করুন)

## ২. Unit test যা কভার করে
| ফাইল | কী যাচাই করে |
|---|---|
| `PageRangeParserTest` | `1-3, 5, 8-10`, উল্টো range, 0/বেশি পৃষ্ঠা, ফাঁকা, একপৃষ্ঠা, ৫০০ পৃষ্ঠা, ভুল চিহ্ন |
| `PageMappingTest` | print system-এর চাওয়া পৃষ্ঠা থেকে মূল PDF-এর সঠিক পৃষ্ঠা বের করা |
| `PrintSummaryTest` | নিচের একসারি সারাংশ (`A4 • Color • Double-sided • 3 pages`) |
| `PrintTransformTest` | N-up/booklet/watermark কখন লাগে, orientation/duplex সমন্বয় |
| `PrintJobWatcherTest` | job status বাছাই ও বার্তা |
| `PageOrderTest` | Reverse ও guided manual duplex-এর ক্রম (৪০ পৃষ্ঠা, বিজোড় সংখ্যায় ফাঁকা পাতা, দুই stack style, resume) |
| `SessionPhaseTest` | guided প্রিন্টের ধাপ বদল: pass 1 শেষ → pass 2-এর জন্য প্রস্তুত, cancel/fail → থেমে গেছে |
| `PrivacyCleanupTest` | পুরোনো temporary ফাইল মোছা |

**কভার হয়নি (device বা Robolectric লাগবে):** `PrintAttributesMapper` (Android `PrintAttributes` ক্লাস), `PdfPrintDocumentAdapter`-এর আসল লেখা, PDF তৈরির (`PrintPreprocessor`) আউটপুট, Compose UI।

## ৩. ডিভাইসে হাতে পরীক্ষা
প্রতিটি ঘরে ✔ বা ✘ ও ডিভাইস/printer-এর নাম লিখুন।

| # | পরীক্ষা | প্রত্যাশিত ফল | ফল |
|---|---|---|---|
| 1 | Files বা Home থেকে PDF বেছে Print | Print Properties স্ক্রিন খোলে | |
| 2 | Back চাপা | আগের Files/Home ট্যাবে ফেরে | |
| 3 | ১ পৃষ্ঠার PDF | স্ক্রিন ঠিক খোলে, প্রিন্ট হয় | |
| 4 | ৫০০+ পৃষ্ঠার PDF | UI সাড়া দেয়, crash/ANR নেই | |
| 5 | Pages: `1-3, 5, 8-10` | শুধু ঐ ৭ পৃষ্ঠা প্রিন্ট হয় (কাগজে গুনে দেখুন) | |
| 6 | Pages: `8-3`, `0`, বেশি সংখ্যা | ফিল্ডের নিচে error, "Continue" বন্ধ | |
| 7 | Copies | অ্যাপের স্ক্রিনে copies ঘর নেই; Android print screen-এ বদলে প্রিন্ট করুন | |
| 8 | A4 / Letter, portrait / landscape | system print screen-এ শুরুর মান মেলে | |
| 9 | Color / Grayscale, duplex | printer সমর্থন করলে প্রযোজ্য হয়; না করলে বাদ যায় | |
| 10 | Wi‑Fi printer | system UI-তে বেছে প্রিন্ট হয় | |
| 11 | Printer বন্ধ/offline | Android notification ও অ্যাপের বার্তা পরিষ্কার | |
| 12 | USB OTG printer | সামঞ্জস্যপূর্ণ driver থাকলে চলে; না থাকলে UI-র সীমাবদ্ধতার লেখা মেলে | |
| 13 | Password দেওয়া PDF | crash নেই, "Can't print this PDF" বা unlock বার্তা | |
| 14 | নষ্ট (corrupt) PDF | crash নেই, বার্তা আসে | |
| 15 | প্রিন্ট চলাকালে Cancel | job বাতিল হয়, অ্যাপ ঠিক থাকে | |
| 16 | Dark/light theme, ছোট স্ক্রিন | সব পড়া ও ব্যবহারযোগ্য | |
| 17 | N-up / booklet / watermark / page number | print screen-এর preview ও কাগজের আউটপুট মেলে | |
| 18 | প্রিন্টের পর `cache/print-tmp` | খালি (Android Studio Device Explorer বা `adb shell run-as`) | |
| 19 | মূল PDF | আকার ও পরিবর্তনের সময় অপরিবর্তিত | |
| 20 | Printer না থাকা | "Share PDF instead" কাজ করে | |
| 21 | Page order = Reverse (৪ পৃষ্ঠার PDF) | কাগজে ৪, ৩, ২, ১ ক্রমে বের হয় | |
| 22 | Manual two-sided: "Test with a 4-page sample" → pass 1 → কাগজ উল্টে → pass 2 | Sheet 1-এ Page 1 ও 2, Sheet 2-এ Page 3 ও 4; "TOP OF PAGE" দুই পিঠেই ওপরে। ক্রম ভুল হলে "Paper after pass 1" বদলান; উল্টো এলে "Pages of pass 2" = Upside down | |
| 23 | Manual two-sided, ৪০ পৃষ্ঠা ও ৫ পৃষ্ঠার PDF | ২০ ও ৩টি কাগজ; ৫ পৃষ্ঠায় শেষ কাগজের পিঠ ফাঁকা | |
| 25 | প্রিন্ট শুরু করে Home চেপে অ্যাপ থেকে বেরিয়ে যান (এবং Recent থেকে অ্যাপ সরান) | প্রিন্ট চলতে থাকে; "Printing..." নোটিফিকেশন ও "Cancel printing" বোতাম থাকে | |
| 26 | নোটিফিকেশনের "Cancel printing" চাপা | job বাতিল হয়, "Printing cancelled" নোটিফিকেশন আসে; অন্য কিছুতে বাতিল হয় না | |
| 27 | Manual two-sided: pass 1 শেষ হলে (অ্যাপের বাইরে থেকে) | "Pass 1 finished" নোটিফিকেশন; "Start pass 2" চাপলে অ্যাপ খুলে pass 2-এর print screen আসে | |
| 28 | pass 1 চলাকালে অ্যাপ জোর করে বন্ধ করে আবার খুলুন | নোটিফিকেশন চাপলে বা ঐ PDF-এর Print স্ক্রিনে গেলে ধাপের ডায়ালগ ফিরে আসে | |
| 29 | Android 13+ ডিভাইসে প্রথম প্রিন্ট | নোটিফিকেশন অনুমতি চায়; "Don't allow" দিলেও প্রিন্ট চলে | |
| 24 | Pass মাঝপথে থামলে "Stopped midway" → সংখ্যা দিন | বাকি কাগজ থেকে ঠিক জায়গায় চালু হয় (ফাঁকা কাগজও গোনা হয়) | |

## ৪. Definition of Done-এর অবস্থা
| শর্ত | অবস্থা |
|---|---|
| Print action ও Print Properties স্ক্রিন | কোডে আছে; ডিভাইসে যাচাই বাকি (#1) |
| validation ও সারাংশ | কোডে আছে ও unit test আছে |
| system print UI থেকে printer নির্বাচন | ডিভাইসে যাচাই বাকি (#10) |
| supported settings পাঠানো | কোডে আছে; ডিভাইসে যাচাই বাকি (#8, #9) |
| page range ও multi-page আউটপুট | যুক্তির unit test আছে; কাগজে যাচাই বাকি (#5) |
| cancel/error handling | কোডে আছে; ডিভাইসে যাচাই বাকি (#11, #13–#15) |
| Wi‑Fi printing বাস্তব printer-এ পরীক্ষা | **বাকি** |
| USB সীমাবদ্ধতা UI-তে পরিষ্কার | লেখা আছে; USB-তে যাচাই বাকি (#12) |
| মূল PDF overwrite হয় না | কোডে শুধু পড়া হয়; #19-এ যাচাই করুন |
| debug build ও lint pass | **বাকি** (এখানে চালানো যায়নি) |

## ৪. UI রিডিজাইন QA (Milestone 6) — ডিভাইসে হাতে পরীক্ষা
প্রতিটি ঘরে ✔ বা ✘ ও ডিভাইস/Android সংস্করণ লিখুন। প্রতিটি পরীক্ষা Light ও Dark, English ও বাংলায় একবার করে করুন।

| # | পরীক্ষা | প্রত্যাশিত ফল | ফল |
|---|---|---|---|
| N1 | পাঁচ ট্যাবে ক্রমে ট্যাপ: Home, Files, Tools, Scanner, Settings | প্রতিটি আসল স্ক্রিন খোলে; নির্বাচিত ট্যাবে গোলাপি pill ও গাঢ় লেবেল | |
| N2 | Files-এ ফিল্টার/স্ক্রল করে অন্য ট্যাবে গিয়ে ফেরা | Files আগের ফিল্টার ও স্ক্রল অবস্থানে ফেরে | |
| N3 | Tools → Merge PDF (যেকোনো টুল) খোলা | বার লুকায়; Back দিলে Tools-এ ফেরে ও বার ফেরে | |
| N4 | Files ট্যাব থেকে Back | Home-এ যায়; Home থেকে Back দিলে অ্যাপ বন্ধ | |
| N5 | বার ট্যাপ করতে করতে Back বারবার চাপা | অতিরিক্ত/ডুপ্লিকেট স্ক্রিনে আটকায় না | |
| N6 | Viewer ও Print Properties খোলা | বার দেখায় না | |
| N7 | Tools-এ সার্চ বা Files-এ সার্চ করতে কিবোর্ড খোলা | বার কিবোর্ডের উপরে ভেসে থাকে না, কিবোর্ড বন্ধ হলে ফেরে | |
| N8 | Gesture নেভিগেশন ও ৩-বাটন নেভিগেশন | বারের ব্যাকগ্রাউন্ড নিচ পর্যন্ত; আইকন/লেবেল gesture এলাকায় ঢোকে না; ফাঁক নেই | |
| N9 | Home → Scanner ট্যাব → স্ক্যান শেষে ফল খোলা | স্ক্যানার আগের মতো চলে, PDF সেভ হয় | |
| T1 | Tools Grid ↔ List টগল | একই ২৪টি টুল, একই ক্রম; টগল আইকন বদলায়; পছন্দ মনে থাকে | |
| T2 | Tools সার্চ (Grid ও List), ইংরেজি ও বাংলা নামে/ক্যাটাগরিতে | ফিল্টার ঠিক; ফল না থাকলে খালি-অবস্থা | |
| T3 | ২৪টি টুলের প্রতিটি খুলে দেখা | প্রতিটি নিজের স্ক্রিনে যায় (Scan to PDF ট্যাব হিসেবে খোলে) | |
| H1 | Home: Quick actions চারটি | Scan, Merge, Image to PDF, Compress নিজ নিজ স্ক্রিনে যায় | |
| H2 | Home: সাম্প্রতিক ফাইল (সর্বোচ্চ ৬) ও ⋮ মেনু | খোলা, শেয়ার, প্রিন্ট, নাম বদল, ফোল্ডারে সরানো, মোছা কাজ করে | |
| H3 | Home: "সব দেখুন" | Files, Recent ফিল্টারে খোলে | |
| H4 | Home: সার্চ আইকন | Files খোলে, All ফিল্টার, সার্চ ঘরে ফোকাস ও কিবোর্ড | |
| H5 | কোনো ফাইল না খোলা অবস্থায় Home | "এখনও কোনো সাম্প্রতিক ফাইল নেই" ও "ফাইল দেখুন" বোতাম | |
| F1 | Files: All, Starred, Recent, Unfiled, ফোল্ডার চিপ | প্রতিটি সঠিক তালিকা দেখায়; Starred ফাঁকা হলে "No favorites yet" | |
| F2 | Files: New folder, ফোল্ডারে লং-প্রেস করে মোছা | ফোল্ডার তৈরি/মোছা হয়; ফাইল Unfiled-এ ফেরে | |
| F3 | Files: সার্চ (নাম ও PDF-এর ভেতরের লেখা), সর্টিং মেনু, গ্রিড/লিস্ট | আগের মতো কাজ করে | |
| F4 | Files: লং-প্রেসে মাল্টি-সিলেকশন, Star, Share, Print(১টি), Delete, Use in tool, Select all, Move | আগের মতো কাজ করে; Back সিলেকশন বাতিল করে | |
| F5 | Files: pull-to-refresh | নতুন ফাইল দেখায় | |
| F6 | ফাইল-অ্যাক্সেস পারমিশন না দেওয়া অবস্থায় Home ও Files | পারমিশন স্ক্রিন; Allow চাপলে সেটিংসে নেয়; ফিরে এলে তালিকা আসে | |
| F7 | Home-এ ফাইল Star/নাম বদলে Files-এ যাওয়া | Files-এ একই পরিবর্তন (একই ViewModel) | |
| S1 | Settings: System/Light/Dark | অ্যাপ সাথে সাথে বদলায়; অ্যাপ বন্ধ করে খুললে মনে থাকে | |
| S2 | Settings: Wallpaper colors (Android 12+) | চালু/বন্ধে রঙ বদলায়; Android 11-এ সারিটি নেই | |
| S3 | Settings: Default sorting | Files-এর সর্টিং বদলায়, Files-এর Sort মেনুর সাথে মেলে | |
| S4 | Settings: Search inside PDFs বন্ধ করা | "Search inside PDFs is off. The stored text was deleted" বার্তা; চালু করলে ইনডেক্সিং শুরু | |
| V1 | ছোট ফোন (~320dp প্রস্থ) ও বড় ফোন/ট্যাবলেট | লেবেল কাটা বা ওভারল্যাপ নেই | |
| V2 | Landscape | বার ও স্ক্রিন ব্যবহারযোগ্য; কিছু কাটা পড়ে না | |
| V3 | সিস্টেম ফন্ট সাইজ সবচেয়ে বড় | বারের লেবেল ও কার্ডের লেখা কাটে না | |

