# MinimalFlow Launcher ProGuard / R8 rules.
#
# The launcher ships no reflection-based serialisation, so the default Android
# rules plus the ones below are sufficient. They exist to make the release build
# reproducible and to keep Room / kotlinx.serialization metadata intact.

# Keep line numbers for readable crash reports, hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room generated implementations are instantiated reflectively by Room_Impl lookup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# kotlinx.serialization keeps generated serializers reachable through the companion.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.minimalflow.launcher.core.backup.** {
    *** Companion;
}
-keepclasseswithmembers class com.minimalflow.launcher.core.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.minimalflow.launcher.**$$serializer { *; }
-keepclassmembers class com.minimalflow.launcher.** {
    *** Companion;
}
-keepclasseswithmembers class com.minimalflow.launcher.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The widget host receiver is instantiated by the framework from the manifest.
-keep class com.minimalflow.launcher.core.widgetkit.WidgetHostReceiver { *; }

# MinimalFlow does not publish a widget of its own, so no android.appwidget.provider
# metadata exists. Providers installed by the *user* inflate their own views, and
# AppWidgetHost needs to find their two- and three-argument constructors reflectively.
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Hilt generated components.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-dontwarn dagger.hilt.**

# Compose keeps its own rules; silence the warnings emitted by the compiler plugin.
-dontwarn org.jetbrains.annotations.**

# OkHttp / Okio are not part of the dependency graph (Coil core is used without a
# network fetcher); keep R8 from complaining if a transitive module references them.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
