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

# FFmpegKit — keep the JNI bridge classes and their callbacks; R8 must not
# rename or strip them or native code can't find them.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# WhisperLib — JNI symbols are bound to com.whispercpp.whisper.WhisperLib
# method names. R8 must keep the class and external fun signatures intact.
-keep class com.whispercpp.whisper.** { *; }
-keepclasseswithmembernames class com.whispercpp.whisper.** {
    native <methods>;
}