# Keep kotlinx.serialization models reflective metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.carteltv.tv.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.carteltv.tv.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI entry points are resolved by symbol name (Java_com_carteltv_tv_engine_*).
-keepclasseswithmembers,includedescriptorclasses class com.carteltv.tv.engine.** {
    native <methods>;
}

# smbj (SMB recording targets): desktop-only EL and Kerberos classes are never reached on Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
# smbj dispatches events through mbassador, which finds @Handler methods by reflection.
-keep class net.engio.mbassy.** { *; }
-keep class com.hierynomus.** { *; }
-keepattributes RuntimeVisibleAnnotations,Signature
