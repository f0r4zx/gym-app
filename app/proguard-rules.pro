# kotlinx.serialization keeps generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class app.gymquest.**$$serializer { *; }
-keepclassmembers class app.gymquest.** {
    *** Companion;
}
-keepclasseswithmembers class app.gymquest.** {
    kotlinx.serialization.KSerializer serializer(...);
}
