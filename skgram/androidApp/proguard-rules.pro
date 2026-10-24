# TDLib JNI bridge (the library ships its own consumer rules; these are belt and braces)
-keep class org.drinkless.tdlib.** { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
# DTOs are (de)serialized by kotlinx.serialization inside tdl-coroutines
-keep class dev.g000sha256.tdl.dto.** { *; }
-dontwarn dev.g000sha256.tdl.**
