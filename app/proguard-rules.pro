# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.jonas.openchat.**$$serializer { *; }
-keepclassmembers class dev.jonas.openchat.** {
    *** Companion;
}
-keepclasseswithmembers class dev.jonas.openchat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# PDFBox
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class org.apache.** { *; }
-dontwarn org.apache.**

# Keep Room generated implementations
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
