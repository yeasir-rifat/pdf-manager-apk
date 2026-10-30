# Print: permissions ও privacy (ধাপ ১১)

## Permissions
- Print Framework ব্যবহারে নতুন কোনো permission লাগে না। USB, নেটওয়ার্ক বা storage permission যোগ করা হয়নি।
- আগে থেকে থাকা permission (`MANAGE_EXTERNAL_STORAGE` লাইব্রেরিতে অন্য অ্যাপের PDF দেখার জন্য। OCR সরানোর পর `INTERNET` বাদ গিয়েছিল, কিন্তু Word/Excel/PowerPoint → PDF যোগ হওয়ায় ফিরে এসেছে; নিচে "Office → PDF" অংশ দেখুন) এই ধাপে বদলানো হয়নি।
- Wi‑Fi printer খোঁজা configured Android print service করে। অ্যাপ নিজে নেটওয়ার্ক স্ক্যান করে না।
- ধাপ ১৩-র পর: `POST_NOTIFICATIONS` (প্রিন্টের অগ্রগতি ও "কাগজ উল্টান" নোটিফিকেশন), `FOREGROUND_SERVICE` ও `FOREGROUND_SERVICE_DATA_SYNC` (অ্যাপ বন্ধ করলেও print job অনুসরণ) যোগ হয়েছে। নোটিফিকেশন অনুমতি না দিলেও প্রিন্ট চলে, শুধু নোটিফিকেশন দেখা যায় না।
- Raw USB printing নেই, তাই USB host permission-ও নেই।

## অ্যাপ যে ডেটা রাখে
| কী | কোথায় | কখন মোছে |
|---|---|---|
| প্রিন্টের জন্য temporary PDF (N-up, booklet, watermark, page number) | `cache/print-tmp` | print job শেষ হলে; ব্যর্থ হলে সঙ্গে সঙ্গে; অ্যাপ প্রসেস চালুর সময় বাকি সব; ৬ ঘণ্টার পুরোনো ফাইল পরের প্রস্তুতির সময় |
| পেজ preview (thumbnail) | `cache/thumbs` | ১৪ দিনের পুরোনো হলে প্রসেস চালুর সময়; মোট ৬০ MB ছাড়ালে পুরোনোগুলো আগে |
- চলমান দুই-ধাপের (manual two-sided) প্রিন্টের জন্য ফাইলের ঠিকানা, নাম ও বাছাই করা সেটিংস অ্যাপের নিজস্ব `print-session` সেটিংসে রাখা হয় (PDF-এর বিষয়বস্তু নয়)। দুই ধাপ শেষ হলে বা "Discard" চাপলে মুছে যায়।
| PDF-এর ভেতরের লেখার ইনডেক্স (Library search) | অ্যাপের নিজস্ব ডাটাবেস `databases/pdf_text.db` | ফাইল মুছে গেলে বা বদলালে ওই ফাইলের অংশ; সার্চ মেনুতে "Search inside PDFs" বন্ধ করলে সব; অ্যাপের ডেটা মুছলে বা আনইনস্টলে |
| Repair-এর অস্থায়ী ফাইল | `cache/repair-tmp` | কাজ শেষে, ব্যর্থ হলেও সঙ্গে সঙ্গে |
- ইনডেক্সে প্রতি PDF-এর লেখা (ছোট হাতের অক্ষরে, প্রথম ৪ লাখ অক্ষর পর্যন্ত) রাখা হয়, তাই এটি সংবেদনশীল। `pdf_text.db` অটো-ব্যাকআপ ও ফোন-থেকে-ফোনে স্থানান্তর থেকে বাদ (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`)। এটি অ্যাপের নিজস্ব ফোল্ডারে থাকে, অন্য অ্যাপ পড়তে পারে না। ফাইলটি আলাদাভাবে এনক্রিপ্ট করা হয় না।
- মূল PDF কখনো বদলানো বা overwrite করা হয় না।
- `cache` ফোল্ডার Android-এর Auto Backup-এ যায় না।
- Delete সাধারণ ফাইল মোছা; ওপর থেকে overwrite করা হয় না।

## Log ও analytics
- PDF-এর কোনো বিষয়বস্তু log বা analytics-এ লেখা হয় না (কোডে `Log`/`println`/analytics নেই)।

## যা অ্যাপের হাতের বাইরে
- ফাইলের নাম print job-এর নাম হিসেবে Android print service ও print notification-এ যায়। PDF-এর বিষয়বস্তু print service-এ যায়, তাই প্রিন্টে কী হয় তা ওই service ও printer নির্মাতার নীতির ওপর নির্ভর করে।
- Library-র URI অ্যাপের নিজস্ব scan থেকে আসে। Document picker-এর URI শুধু ঐ টুলের সময়ের জন্য বৈধ; permission হারালে Print স্ক্রিন "Can't print this PDF" দেখায়।

# Office → PDF: Word, Excel, PowerPoint (পরিকল্পনা M1)
- `INTERNET` permission ফিরে এসেছে। অ্যাপ নেটওয়ার্ক **শুধু** এই তিন টুলে "Convert" চাপলে ব্যবহার করে; অন্য কোনো টুল ইন্টারনেট ছোঁয় না।
- ফাইল TLS-এ ইউজারের **নিজের** Google Drive-এ সাময়িক আপলোড হয়, Google সেখানে PDF করে, তারপর ফাইল স্থায়ীভাবে মোছা হয়। অ্যাপের নিজস্ব কোনো সার্ভার নেই।
- OAuth scope শুধু `drive.file`: অ্যাপ নিজে যে ফাইল বানায় কেবল সেটুকুই দেখতে পারে, ইউজারের অন্য কোনো Drive ফাইল নয়।
- Drive-এ ফাইলের নাম আসল নাম নয় (`pdfm-temp-…`)। access token শুধু মেমোরিতে থাকে, কোথাও সেভ হয় না।
- এই অংশ পরিকল্পনা অনুযায়ী ধাপে ধাপে যোগ হচ্ছে; এখন (M2) পর্যন্ত আছে: `INTERNET`, লজিক-ফাইল ও Google অনুমতির কোড (`GoogleDriveAuth`)। আপলোড/কনভার্ট কোড এখনো নেই। অনুমতি তুলে নিতে: myaccount.google.com/permissions।
