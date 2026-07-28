# Content JSON is parsed with kotlinx.serialization into @Serializable models.
# R8 must keep the generated serializers or the whole content library fails at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.bastion.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.bastion.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bastion.app.**$$serializer { *; }

# Services referenced only from the manifest.
-keep class com.bastion.app.guard.** extends android.accessibilityservice.AccessibilityService
-keep class com.bastion.app.guard.** extends android.net.VpnService
