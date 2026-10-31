# PDF Manager — নতুন ফিচার রোডম্যাপ

Kotlin + Jetpack Compose, PDFBox-Android, Room। নতুন সব টুল একই ছাঁচে বানানো হচ্ছে:
`ToolScaffold` + `ToolJob` + `OutputStore.savePdf` (আউটপুট যায় `Documents/PDF Manager`-এ)।

## ডিজাইন নিয়ম (সব স্ক্রিনে প্রযোজ্য)

- **হেল্পার টেক্সট নেই।** কার্ডের নিচে ব্যাখ্যা বা হিন্ট লাইন থাকবে না। শুধু লেবেল, কন্ট্রোল আর বাটন।
- **কোনো টেক্সট লাইন ভেঙে নিচে নামবে না।** সব লেবেল `FitText` দিয়ে: এক লাইন, জায়গা কম হলে ফন্ট নিজে ছোট হয়, তারপরও না ধরলে `…`।
- ছোট টাইটেল: টুলের নাম ১২ অক্ষরের মধ্যে রাখা (গ্রিডে আধা-চওড়া জায়গা)।
- থিম, কার্ড, বাটন, চিপ — আগে থেকে থাকা `AppCard`, `OptionCard`, `ChipRow`, `PrimaryButton` ব্যবহার।
- থাম্বনেইল পিকার সব পেজ-ভিত্তিক টুলে একই কম্পোনেন্ট (`PageGrid`)।

## ধাপসমূহ

### ধাপ ০ — ভিত্তি
- [x] `FitText` (এক লাইন, অটো-শ্রিঙ্ক)
- [x] নতুন আইকন (`AppIcons`)
- [x] `PageGrid` — থাম্বনেইল গ্রিড, সিলেক্ট ও ড্র্যাগ-রিঅর্ডার সাপোর্টসহ
- [x] টুলস হোম নতুন চার গ্রুপে সাজানো: Organize / Edit / Convert & Extract / Privacy & Utility
- [x] `data/PdfPages.kt` — পেজ-লেভেল অপারেশনের ইঞ্জিন

### ধাপ ১ — Organize
- [x] **Split PDF** — রেঞ্জ (`1-3, 5, 8-10`) অথবা প্রতি N পেজ পরপর
- [x] **Extract / Delete pages** — থাম্বনেইল দেখে বেছে রাখা বা মুছে ফেলা
- [x] **Reorder pages** — থাম্বনেইল ড্র্যাগ করে ক্রম বদল
- [x] **Add blank page** — যেকোনো জায়গায় ফাঁকা পেজ

### ধাপ ২ — Edit
- [x] **Watermark** — লেখা বা ছবি, ঘোরানো, স্বচ্ছতা
- [x] **Page numbers** — শুরুর নম্বর, অবস্থান (৬টি)
- [x] **Sign PDF** — আঙুলে সই এঁকে পেজে বসানো
- [x] **Highlight / Notes**
- [x] **Resize paper** — A4 / A5 / Letter
- [x] **Poster print** — বড় পেজ কয়েকটি A4 শিটে

### ধাপ ৩ — Convert / Extract
- [x] **PDF to Text** — পেজ রেঞ্জ, পড়ার ক্রম, পেজ মার্ক; `.txt` হিসেবে `Documents/PDF Manager`-এ
- [x] **Extract images** — PDF-এর ভেতরের ছবি JPG/PNG হিসেবে `Pictures/PDF Manager`-এ (গ্রিডে নাম: Get images)
- [x] **Text to PDF** — লিখে/পেস্ট করে বা `.txt` ফাইল থেকে; বাংলা সমর্থিত

### ধাপ ৪ — Privacy ও ইউটিলিটি
- [x] **Edit metadata** — Title, Author, Subject, Keywords বদল / সব মুছে ফেলা (গ্রিডে নাম: Metadata)
- [x] **Repair PDF** — Quick fix (পুরো ফাইল নতুন করে লেখা) অথবা Rebuild pages (পেজ ধরে ধরে নতুন ফাইলে)
- [x] **Library search** — সব PDF-এর ভেতরের লেখায় খোঁজা (আলাদা Room ডাটাবেস `pdf_text.db`, নিচের নোট দেখুন)

### Office → PDF (Word, Excel, PowerPoint — ইউজারের নিজের Google Drive দিয়ে)
পরিকল্পনা: `README-OFFICE-TO-PDF.md`। মাইলস্টোন ধরে ধরে যোগ হচ্ছে; তিনটি টুল এখন Convert to PDF গ্রুপে দেখা যায়।
- [x] **M1** — `OfficeHelpers` (খাঁটি লজিক), `INTERNET` permission, ডিপেন্ডেন্সি
- [x] **M2** — `GoogleDriveAuth` + `DriveAuthUi` (drive.file অনুমতি, টোকেন শুধু মেমোরিতে)
- [x] **M3** — `DriveClient` (resumable আপলোড+কনভার্ট, PDF এক্সপোর্ট, মোছা, মার্কার-খোঁজা), `DriveTempJournal` + `DriveLeftovers`, `OfficeConvert` ইঞ্জিন
- [x] **M4** — `OfficeScreens.kt` (একটি স্ক্রিন, ৩টি রুট: `word2pdf`, `excel2pdf`, `ppt2pdf`), `ToolCatalog` ২৭টি টুল, strings (en+bn), ৩টি আইকন, প্রথমবারের সম্মতি-ডায়ালগ, Settings-এ "Google Drive" অংশ
- [x] **M5** — টাইমআউটের আলাদা বার্তা, অ্যাপ-চালুতে নিঃশব্দ পুনঃচেষ্টা (`PrivacyCleanup` → `OfficeConvert.retryLeftovers`), Settings-এ "Remove leftover temp files" (`DriveLeftovers.sweepAll`); WorkManager নেওয়া হয়নি
- [~] **M6** — কোড-রিভিউ ও যাচাই-নির্দেশিকা হয়েছে (`OFFICE-TO-PDF-VERIFY.md`); কম্পাইল/টেস্ট/R8/ডিভাইস পরীক্ষা এখনো বাকি (CI-তে চালাতে হবে)
- [~] **M7** — ডকুমেন্টেশন চূড়ান্ত (এই README, `PRIVACY-NOTES.md`, নিচের "Office → PDF রিলিজ চেকলিস্ট"); `versionCode`/`versionName` বাড়ানো, OAuth publishing status ও `v*` ট্যাগ বাকি, কারণ এগুলো M6 (CI সবুজ + ডিভাইস পরীক্ষা) শেষ হওয়ার পর

### ধাপ ৫ — পরীক্ষা
- [ ] `TEST-CHECKLIST.md`-এ নতুন টুলগুলোর চেকলিস্ট
- [ ] `PageRangeParser`-এর মতো নতুন লজিকের ইউনিট টেস্ট

## ধাপ ২-এর নোট

- Watermark-এর লেখা Android নিজে ছবি করে আঁকে, তাই বাংলা নামও কাজ করে। প্রিভিউ আর PDF-এ একই ছবি যায়।
- Page numbers Helvetica ফন্টে, শুধু সংখ্যা ও ইংরেজি লেখা।
- Highlight পেজের কনটেন্টে Multiply ব্লেন্ডে বসে (সব ভিউয়ারে দেখা যায়)। Note একটি স্ট্যান্ডার্ড PDF Text annotation; ভিউয়ার অনুযায়ী আইকন দেখায়।
- Highlight এখন আঁকা আয়তক্ষেত্র; লেখা স্বয়ংক্রিয়ভাবে ধরে না।
- Resize/Poster-এ লিংক, বুকমার্ক আর ফর্ম ফিল্ড থাকে না (পেজ ছবি-আকারে নতুন পেজে বসে)।

## ধাপ ৩-এর নোট

- **PDF to Text** শুধু PDF-এর ভেতরের লেখা পড়ে। স্ক্যান করা PDF-এ লেখা নেই (শুধু ছবি), আর OCR অ্যাপ থেকে সরানো হয়েছে, তাই সেখানে "No text found" দেখায়। পাসওয়ার্ড দেওয়া PDF আগে Lock / Unlock দিয়ে খুলতে হয়।
- বাংলা লেখা বের হবে PDF কীভাবে বানানো তার ওপর। অনেক বাংলা PDF-এ ফন্টের ভেতরে লেখার সঠিক কোড থাকে না, তখন ভাঙা বা এলোমেলো অক্ষর আসতে পারে। এটা অ্যাপের ভুল নয়, ফাইলের গঠনের সীমা।
- "Reading order": Normal = PDF যে ক্রমে লিখেছে। By position = পেজের ওপর থেকে নিচে, বাঁ থেকে ডানে সাজানো (একাধিক কলামের পেজে Normal প্রায়ই ভালো)।
- Page marks অন হলে প্রতি পেজের আগে `--- Page 3 ---` লাইন বসে, লেখাহীন পেজেও। অফ থাকলে লেখাহীন পেজ বাদ যায়, আর বার্তায় কতগুলো পেজে লেখা ছিল না তা জানানো হয়।
- ফাইলের নাম: `Report.txt`; কিছু পেজ বাছলে `Report_p1-3_5.txt`। একই নাম থাকলে সিস্টেম নিজে `(1)` যোগ করে, পুরোনো ফাইল ওভাররাইট হয় না।
- **Extract images** ছবিগুলো আবার এনকোড করে সেভ করে (JPG মান ৯৫, অথবা লসলেস PNG), তাই PDF-এর ভেতরের মূল বাইটের সঙ্গে হুবহু এক নয়। স্বচ্ছ ছবি JPG-তে সাদা জমিনে বসে।
- একই ছবি একাধিক পেজে থাকলে একবারই সেভ হয় (প্রথম পেজের নামে)। ফাইলের নাম: `Img_<PDF-এর নাম>_p003_01.jpg` (পেজ নম্বর ও ছবির ক্রম, সব সময় ইংরেজি অঙ্কে)।
- "Smallest image" ঠিক করা আকারের চেয়ে দুই দিকেই ছোট ছবি (আইকন, সাজসজ্জা) বাদ দেয়। ডিফল্ট ৬৪ px; "Any" দিলে সব ছবি আসে।
- ৪০ মেগাপিক্সেলের বেশি ছবি, বা যে ছবি ডিকোড করা যায় না (যেমন JPEG 2000), বাদ যায় ও বার্তায় গোনা হয়। ভেক্টর ছবি (আঁকা আকার) ছবি নয়, তাই এই টুলে আসে না। পুরো পেজ ছবি করতে PDF to Image ব্যবহার করুন।
- **Text to PDF** লেখা আঁকে অ্যান্ড্রয়েডের নিজস্ব টেক্সট ইঞ্জিনে, তাই বাংলা যুক্তাক্ষর ঠিক বসে। ফন্ট ফোনের সিস্টেম ফন্ট (Sans / Serif / Mono); বাংলা অক্ষর ফোনের বাংলা ফন্টে যায়। কাগজ: A4, A5, Letter।
- ট্যাব চার স্পেস হয়ে যায়, লাইন ভাঙা এক ধরনের (`\n`) করা হয়। টেক্সট ফাইল UTF-8 (বা BOM-সহ UTF-8/UTF-16) হিসেবে পড়া হয়; UTF-8 না হলে Windows-1252।
- সীমা: টেক্সট ফাইল ২০ MB, লেখা ৫০ লাখ অক্ষর পর্যন্ত।
- ধাপ ৩-র লজিকের (ফাইলের নাম, টেক্সট জোড়া, পেজ ভাগ, এনকোডিং ইত্যাদি) ইউনিট টেস্ট `ConvertHelpersTest`-এ। নতুন টুলগুলোর ডিভাইস চেকলিস্ট ধাপ ৫-এ।

## ধাপ ৪-এর নোট

- **Edit metadata** শুধু Information dictionary-র চারটি ঘর (Title, Author, Subject, Keywords) বদলায়। শুধু যে ঘর বদলেছে সেটাই লেখা হয়; ফাঁকা রাখলে ঘরটি মুছে যায়। বদলালে Modified তারিখ এখনকার সময় হয়। "Stored in this PDF" লাইনে ফাইলে কী কী আছে দেখায় (Creator, Producer, তারিখ, কাস্টম ঘর, XMP সহ)।
- **XMP:** অনেক ভিউয়ার Information dictionary-র চেয়ে XMP ব্লককে আগে ধরে। তাই ঘর বদলালে ফাইলে XMP থাকলে সেটি বাদ যায় (নইলে পুরোনো নাম দেখাত) এবং বার্তায় "XMP removed" লেখা থাকে। অন্য কিছু XMP-তে থাকলে (যেমন কপিরাইট) তাও যায়।
- **Remove all** Information dictionary আর XMP দুটোই মুছে দেয়। এতে অ্যানোটেশনের লেখকের নাম, সংযুক্ত ফাইল, বা PDF-এর ভেতরের লেখা/ছবিতে থাকা তথ্য যায় না।
- ফলাফল নতুন ফাইল: `Info_<নাম>.pdf` / `Clean_<নাম>.pdf`। মূল ফাইল বদলায় না। পাসওয়ার্ড বা সুরক্ষা দেওয়া PDF আগে Lock / Unlock দিয়ে খুলতে হয়। ডিজিটাল সই করা PDF-এর সই এই টুলে ভেঙে যায়।
- **Repair PDF:** PDFBox-এর সহনশীল রিডার ভাঙা cross-reference টেবিল নিজে নতুন করে বানিয়ে নেয়। *Quick fix* পুরো ফাইল সেই রিডারে পড়ে পরিষ্কার কাঠামোয় আবার লেখে; বুকমার্ক, লিংক, ফর্ম সব থাকে। *Rebuild pages* পেজগুলো একটা একটা করে নতুন ফাইলে কপি করে, যে পেজ পড়া যায় না সেটি বাদ দেয় ও বার্তায় নম্বর জানায়; বুকমার্ক, লিংক আর ফর্ম ফিল্ড থাকে না।
- ফাইল বাছার সঙ্গে সঙ্গে Android-এর নিজস্ব PDF রিডার (ভিউয়ার যেটা ব্যবহার করে) দিয়ে খুলে দেখা হয়: "Opens in the viewer" বা "The viewer cannot open this PDF"। মেরামতের পর ফলাফলও একই রিডারে খুলে দেখা হয় এবং বার্তায় সত্যি বলা হয় ফাইলটি এখন খুলছে কি না। যে ফাইল PDFBox-ও পড়তে পারে না, সেটি মেরামত হয় না (বার্তা: "too damaged")।
- মেরামতের সময় একটি অস্থায়ী ফাইল `cache/repair-tmp`-এ বসে, কাজ শেষে (ব্যর্থ হলেও) মুছে যায়।
- **Library search:** সার্চ বক্সে লিখলে ফাইলের নামের পাশাপাশি PDF-এর ভেতরের লেখাতেও খোঁজে। একাধিক শব্দ দিলে সব শব্দ একই PDF-এ থাকলে মেলে (জায়গা যেখানেই হোক)। এক অক্ষরের শব্দ ধরা হয় না; বড়/ছোট হাতের ও ligature (ﬁ) ফারাক হয় না; বাংলার ZWJ/ZWNJ ধর্তব্য নয়। মেলা অংশ ফাইলের নিচে দেখায়, শব্দগুলো মোটা ও রঙিন। দেখানো অংশ ছোট হাতের অক্ষরে, কারণ ইনডেক্সে সার্চের সুবিধার জন্য ছোট হাতের কপি রাখা হয়।
- ইনডেক্স তৈরি হয় ব্যাকগ্রাউন্ডে, লাইব্রেরি স্ক্যানের পর, নতুন ফাইল আগে। মাঝপথে অ্যাপ বন্ধ হলে পরের বার যেখান থেকে থেমেছিল সেখান থেকে চলে। ফাইল বদলালে (সাইজ বা তারিখ) শুধু সেটি আবার পড়া হয়; মুছে গেলে ইনডেক্স থেকেও যায়। প্রথমবার বড় লাইব্রেরিতে সময় লাগে; সার্চ করার সময় "Reading PDFs for text search: 12 of 80" দেখায়, আর যা ইনডেক্স হয়েছে তাতে সার্চ চলে।
- সীমা: প্রতি PDF-এর প্রথম ৪০০ পেজ ও ৪ লাখ অক্ষর পর্যন্ত, ৮০ MB-এর বড় ফাইল বাদ। স্ক্যান করা PDF (শুধু ছবি), পাসওয়ার্ড দেওয়া ও নষ্ট PDF-এর ভেতরের লেখায় খোঁজা যায় না (নামে খোঁজা চলে)। বাংলা সার্চের মান PDF-এর ফন্টে লেখার কোড ঠিক আছে কি না তার ওপর নির্ভর করে (ধাপ ৩-এর নোট দেখুন)।
- সার্চ বক্সের মেনুর "Search inside PDFs" সুইচ বন্ধ করলে সার্চ শুধু নামে চলে এবং জমানো সব লেখা মুছে যায়। চালু করলে আবার পড়া শুরু হয়।
- **ডাটাবেস সম্পর্কে:** রোডম্যাপে "Room v4" ছিল, কিন্তু ইনডেক্স `pdf_manager.db`-তে না রেখে আলাদা `pdf_text.db`-তে রাখা হয়েছে (`pdf_manager.db` এখনো v3)। কারণ: (১) ইনডেক্সে সব PDF-এর লেখা থাকে, তাই এটি অটো-ব্যাকআপে (ক্লাউডে) যাওয়া উচিত নয়; `res/xml/backup_rules.xml` ও `data_extraction_rules.xml` শুধু `pdf_text.db` বাদ দেয়, ফোল্ডার ও ফেভারিটের ব্যাকআপ আগের মতোই থাকে। (২) ইনডেক্স বড় হলে একই ফাইলে থাকলে অ্যাপের পুরো ব্যাকআপ ২৫ MB সীমা ছাড়িয়ে বন্ধ হয়ে যেত। (৩) ইনডেক্স যেকোনো সময় নতুন করে বানানো যায়, তাই স্কিমা বদলালে মুছে নতুন করে শুরু হয়।
- ধাপ ৪-র খাঁটি লজিকের (সার্চ শব্দ, SQL, snippet, হাইলাইট, মেটাডেটা পরিষ্কার, মেরামতের বার্তা) ইউনিট টেস্ট `SearchHelpersTest` ও `UtilityHelpersTest`-এ। ডিভাইস চেকলিস্ট ধাপ ৫-এ।

## ফাইল ম্যাপ

| ফাইল | কাজ |
|---|---|
| `ui/FitText.kt` | এক লাইনের শ্রিঙ্ক-টু-ফিট টেক্সট |
| `ui/PageGrid.kt` | থাম্বনেইল গ্রিড (সিলেক্ট / ড্র্যাগ) |
| `data/PdfPages.kt` | split, extract, delete, reorder, blank page |
| `ui/OrganizeScreens.kt` | ধাপ ১-এর স্ক্রিন |
| `data/PdfEdit.kt` | watermark, নম্বর, সই, হাইলাইট/নোট, resize, poster ইঞ্জিন |
| `ui/EditCommon.kt` | `PageStage` (পেজ প্রিভিউ + ওভারলে), `PageNav`, `LabeledSlider` |
| `ui/EditScreens.kt` | Watermark, Page numbers, Resize paper, Poster print |
| `ui/SignNotesScreens.kt` | Sign PDF, Highlight/Notes |
| `data/ConvertHelpers.kt` | ধাপ ৩-র খাঁটি লজিক (Android ছাড়া, ইউনিট টেস্টযোগ্য) |
| `data/PdfConvert.kt` | PDF to Text, ছবি বের করা, Text to PDF ইঞ্জিন |
| `ui/ConvertScreens.kt` | PDF to Text, Extract images, Text to PDF স্ক্রিন |
| `data/SearchHelpers.kt` | ধাপ ৪: সার্চের খাঁটি লজিক (fold, শব্দ, SQL, snippet, হাইলাইট) |
| `data/UtilityHelpers.kt` | ধাপ ৪: মেটাডেটা পরিষ্কার ও মেরামতের বার্তার খাঁটি লজিক |
| `data/TextIndex.kt` | আলাদা Room ডাটাবেস `pdf_text.db`, DAO আর ব্যাকগ্রাউন্ড ইনডেক্সার |
| `data/PdfUtility.kt` | Edit metadata ও Repair PDF ইঞ্জিন |
| `ui/UtilityScreens.kt` | Metadata ও Repair PDF স্ক্রিন |
| `LibraryViewModel.kt` | নাম + ভেতরের লেখায় সার্চ, ইনডেক্স চালু/বন্ধ |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | `pdf_text.db` ব্যাকআপ থেকে বাদ |
| `data/OfficeHelpers.kt` | Office→PDF: ধরন, ম্যাজিক-বাইট, আকার, নাম, ত্রুটি-বার্তা, ব্যাকঅফ, ফলাফলের বার্তা (খাঁটি লজিক) |
| `data/GoogleDriveAuth.kt`, `ui/DriveAuthUi.kt` | Google অনুমতি (`AuthorizationClient`, scope `drive.file`) |
| `data/DriveJson.kt` | Drive উত্তর পড়ার ছোট JSON পার্সার (`org.json` নয়, তাই ইউনিট টেস্টে চলে) |
| `data/DriveClient.kt` | `HttpURLConnection`-এ Drive কল: আপলোড+কনভার্ট, export, delete, মার্কার-খোঁজা; ৩ বার চেষ্টা, `401`-এ একবার নতুন টোকেন |
| `data/DriveTempJournal.kt` | অস্থায়ী Drive ফাইলের জার্নাল (`noBackupFilesDir`-এ, ব্যাকআপে যায় না) ও `DriveLeftovers` (পরের বার মোছা) |
| `ui/OfficeScreens.kt` | Word/Excel/PowerPoint to PDF-এর শেয়ার্ড স্ক্রিন (`OfficeToPdfScreen(kind, …)`), সম্মতি-ডায়ালগ |
| `data/OfficeConvert.kt` | Office→PDF ইঞ্জিন; অস্থায়ী PDF `cache/office-tmp`-এ |
| `MainActivity.kt` | রুট যোগ (`pdf2text`, `extractimg`, `text2pdf`, `metadata`, `repair`) |
| `ui/ToolScreens.kt` | টুলস হোম গ্রুপ |

## বিল্ড

### Office → PDF রিলিজ চেকলিস্ট

এই ক্রমে এগোন। আগের ধাপ শেষ না হলে পরেরটায় যাবেন না।

1. **CI সবুজ:** `main`-এ push করে Actions রান দিন। "Build debug APK" লাল হলে লগের `e: file:///…kt:<লাইন>` লাইনগুলো পাঠান। টেস্ট ধাপও সবুজ হতে হবে (`gradle :app:testDebugUnitTest`)।
2. **Google Cloud (একবারের কাজ):** নতুন প্রজেক্ট, Google Drive API চালু, consent screen (External, শুধু `drive.file`), তারপর Android OAuth client: package `com.pdfmanager.app` ও **release keystore-এর SHA-1**। বিস্তারিত `README-OFFICE-TO-PDF.md` §৬। কোডে বা Gradle-এ কোনো client ID/secret বসে না।
3. **ডিভাইস পরীক্ষা:** `KEYSTORE_BASE64` secret সেট করে বানানো release APK দিয়ে `TEST-CHECKLIST.md`-এর O1 থেকে O20 চালান, অন্তত দুটি Android সংস্করণে (একটি 8/9, একটি 13+)। CI-র debug APK-তে Google অনুমতি কাজ করে না। R8 বিল্ড ভাঙলে `-PnoR8` বিল্ডের সঙ্গে তুলনা করুন।
4. **OAuth publishing status বাছুন:**
   - *Testing:* শুধু নিজের অ্যাকাউন্ট test user, কোনো যাচাই লাগে না, তবে অনুমতি ৭ দিনে মেয়াদ হারাতে পারে।
   - *In production:* `drive.file` non-sensitive scope, তাই সাধারণত পূর্ণ verification লাগে না; "unverified app" সতর্কতা আসে কি না ও privacy policy URL লাগে কি না Console-এ দেখে নিন। অন্য মানুষকে দিলে এটিই বেছে নিন।
5. **সংস্করণ:** `app/build.gradle.kts`-এ `versionCode`/`versionName` বাড়ান (এখন 4 / 0.4)।
6. **রিলিজ:** `git tag v<সংস্করণ>` ও `git push --tags`। CI স্থায়ী keystore দিয়ে সই করা APK বানায়। keystore হারালে বা বদলালে SHA-1 বদলায় এবং Google অনুমতি অচল হয়, তখন নতুন SHA-1 দিয়ে আবার OAuth client বানাতে হবে।

**জেনে রাখুন:** Excel-এর পেজ লেআউট Drive API থেকে নিয়ন্ত্রণ করা যায় না; এক্সপোর্ট করা PDF ১০ MB ছাড়ালে Google ব্যর্থ করে; Google Play services লাগে; ইন্টারনেট ও Google অ্যাকাউন্ট লাগে। পূর্ণ তালিকা `README-OFFICE-TO-PDF.md` §৩।

`./gradlew assembleDebug` — আমার এখানে অ্যান্ড্রয়েড SDK নেই, তাই অ্যাপের কোড কম্পাইল করে দেখা হয়নি। শুধু Android-মুক্ত লজিক (`ConvertHelpers`, `PageRangeParser`) আলাদা কম্পাইল করে তাদের ইউনিট টেস্ট চালানো হয়েছিল। ধাপ ৪-এ সেটাও করা যায়নি (এই পরিবেশে Kotlin কম্পাইলার নেই), তাই ধাপ ৪-এর কোড ও `SearchHelpersTest`/`UtilityHelpersTest` কোনোটাই এখনো চালানো হয়নি। CI (`.github/workflows/build-apk.yml`) বা Android Studio-তে বিল্ড করে কম্পাইল এরর থাকলে জানালে ঠিক করে দেব।

## Release সই (স্থায়ী keystore)

আপডেট ইনস্টল হওয়ার জন্য প্রতিটি release APK একই key দিয়ে সই হতে হবে। একবারই এটা বানাতে হয়:

1. নিজের কম্পিউটারে (JDK 17 লাগবে) চালান: `./scripts/create-keystore.sh`। এটা `release.jks` বানায় আর GitHub-এর জন্য চারটি মান ছাপে।
2. GitHub-এ Settings → Secrets and variables → Actions-এ চারটি secret যোগ করুন: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`।
3. `release.jks` ফাইল আর পাসওয়ার্ড নিরাপদ জায়গায় রাখুন (পাসওয়ার্ড ম্যানেজার, আলাদা ব্যাকআপ)। হারালে আপডেট আর ইনস্টল হবে না। ফাইলটা git-এ দেবেন না (`.gitignore`-এ বাদ আছে)।

লোকাল build-এ: `gradle assembleRelease -PkeystoreFile=/path/release.jks -PkeystorePassword=... -PkeyAlias=pdfmanager`। keystore না দিলে release APK debug key দিয়ে সই হয় আর build লগে সতর্কতা আসে।

**একবারের ঝামেলা:** এখন ফোনে থাকা পুরনো APK অন্য (প্রতি রানে নতুন বানানো) key-র, তাই প্রথমবার নতুন সই করা APK ইনস্টলের আগে পুরনোটা আনইনস্টল করতে হবে (ডেটা যাবে)। এরপর থেকে সব আপডেট সরাসরি ইনস্টল হবে। Tag (`v*`) push করলে GitHub Release-এ সই করা release APK যায়।
