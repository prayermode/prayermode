# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ========== Debugging & Obfuscation ==========
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes InnerClasses, Signature, Exceptions, *Annotation*

# ========== Manifest Components ==========
-keep public class com.yahyaoui.prayermode.MainActivity
-keep public class com.yahyaoui.prayermode.SelectionActivity
-keep public class com.yahyaoui.prayermode.InformationActivity
-keep public class com.yahyaoui.prayermode.TermsAndConditions
-keep public class com.yahyaoui.prayermode.dialogs.** { *; }

-keep public class com.yahyaoui.prayermode.LocationService {
    public <init>();
    public void on*();
}
-keep public class com.yahyaoui.prayermode.PrayerTileService {
    public <init>();
    public void on*();
}

-keep public class com.yahyaoui.prayermode.SilentModeReceiver {
    public <init>();
    public void onReceive(android.content.Context, android.content.Intent);
}
-keep public class com.yahyaoui.prayermode.BootReceiver {
    public <init>();
    public void onReceive(android.content.Context, android.content.Intent);
}

# ========== Android Framework ==========
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.service.quicksettings.TileService

# ========== Views & Resources ==========
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(***);
}

# ========== WorkManager ==========
-keep class com.yahyaoui.prayermode.SilentModeWorker { *; }

# ========== OkHttp ==========
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.internal.platform.Platform
-dontwarn okhttp3.**
-keep interface okhttp3.internal.** { *; }

# ========== Kotlin Coroutines ==========
-keepclassmembers class kotlinx.coroutines.internal.DispatchedContinuation {
    *;
}

# ========== ICU4J ==========
-keep class com.ibm.icu.util.Calendar { *; }
-keep class com.ibm.icu.text.DateFormat { *; }
-keep class com.ibm.icu.util.IslamicCalendar { *; }
-dontwarn com.ibm.icu.**

# ========== General Rules ==========
-keep enum * { *; }
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclasseswithmembers class * {
    native <methods>;
}