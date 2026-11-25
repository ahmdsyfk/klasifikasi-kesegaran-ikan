# LiteRT / TFLite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
# kotlinx.serialization (Supabase DTO)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class id.fishfresh.**$$serializer { *; }
-keepclassmembers class id.fishfresh.** { *** Companion; }
-dontwarn org.slf4j.**
