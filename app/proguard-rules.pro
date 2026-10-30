# ---- PDFBox-Android: loads fonts, filters and glyph lists by name at runtime ----
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**
-dontwarn com.gemalto.jp2.**
-dontwarn com.google.errorprone.annotations.**

# ---- ML Kit document scanner (Google Play services ships its own rules; this is a safety net) ----
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---- App classes that Room and the print framework create by reflection ----
-keep class com.pdfmanager.app.data.** { *; }
-keep class com.pdfmanager.app.printing.** { *; }

# Keep line numbers in crash reports
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses
