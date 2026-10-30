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
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# Avoid class/method name obfuscation for your own code
-keepnames class net.primal.** { *; }

# secp256k1 JNI. libsecp256k1-jni.so exports Java_fr_acinq_secp256k1_NativeSecp256k1_*.
# NativeSecp256k1 lives in secp256k1-kmp-jni-common (a jar with no consumer rules).
# secp256k1-kmp-jni-android's consumer proguard already keeps fr.acinq.secp256k1.jni.**
# (NativeSecp256k1AndroidLoader, loaded by reflection). This rule covers the common
# JNI class and the loader; the jni.** part overlaps that AAR rule.
-keep class fr.acinq.secp256k1.** { *; }

# sqlcipher-android 4.9.0. The AAR consumer proguard already keeps net.zetetic.**
# native methods, public constructors, and mNativeHandle, plus the JNI callback
# fields on SQLiteCustomFunction and SQLiteDebug$PagerStats. There is no
# net.sqlcipher.** package in this artifact (that was the older sqlcipher-for-android).
# The keep below overlaps the AAR rule and also retains every member name, because
# the native library looks members up by exact name. ReleaseSecurityModule constructs
# net.zetetic.database.sqlcipher.SupportOpenHelperFactory directly.
-keep class net.zetetic.** { *; }

# PCOC bridge JNI (pcoc-bridge/bridge/src/jni.rs). The .so looks up
# Java_net_primal_android_pcoc_bridge_PcocNative_{indexUrl,bariUrl,verify,yieldedPublicKey,startProduce,stopProduce}
# and calls ProduceListener.onEvent(String) from the produce thread.
# No data class crosses JNI; payloads are String and byte[].
# CborScan in net.primal.android.pcoc.admit.ObjectPublicKey is a private Kotlin
# scanner, not a library and not a JNI type, so it needs no keep.
-keep class net.primal.android.pcoc.bridge.PcocNative { *; }
-keep class net.primal.android.pcoc.bridge.ProduceListener { *; }

# JNA subpackages used by breez_sdk_spark uniffi bindings (Cleaner, *ByReference).
# jna 5.18.1 ships no consumer rules. breez-sdk-spark-kmp 0.23.1 already keeps
# com.sun.jna.* (one package level) and public members of its subclasses, plus
# breez_sdk_spark.** and technology.breez.spark.*. Those consumer rules cover the
# uniffi generated package (callbacks, FfiConverters, vtables). They do not keep
# com.sun.jna.internal or com.sun.jna.ptr, which JNA reads by reflection.
-keep class com.sun.jna.internal.** { *; }
-keep class com.sun.jna.ptr.** { *; }

# kotlinx-serialization 1.11.0 already ships META-INF/proguard rules that keep
# @Serializable Companion fields, serializer() methods, and $$serializer.descriptor.
# Hilt 2.59.2 / Dagger consumer rules already keep @EntryPoint types and
# @dagger.internal.KeepFieldType fields. Room 2.8.4 already keeps every
# RoomDatabase subclass, including generated *_Impl, and its no-arg constructor
# (KClassUtil loads <database>_Impl by Class.forName). No extra rules for those.

# BicoinJ-Core
-dontwarn org.slf4j.impl.StaticLoggerBinder


############
# Retrofit #
############

# Retrofit does reflection on generic parameters. InnerClasses is required to use Signature and
# EnclosingMethod is required to use InnerClasses.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Retrofit does reflection on method and parameter annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Keep annotation default values (e.g., retrofit2.http.Field.encoded).
-keepattributes AnnotationDefault

# Retain service method parameters when optimizing.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Ignore annotation used for build tooling.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Ignore JSR 305 annotations for embedding nullability information.
-dontwarn javax.annotation.**

# Guarded by a NoClassDefFoundError try/catch and only used when on the classpath.
-dontwarn kotlin.Unit

# Top-level functions that can only be used by Kotlin.
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# With R8 full mode, it sees no subtypes of Retrofit interfaces since they are created with a Proxy
# and replaces all potential values with null. Explicitly keeping the interfaces prevents this.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# Keep inherited services.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# R8 full mode strips generic signatures from return types if not kept.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

# With R8 full mode generic signatures are stripped for classes that are not kept.
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# OkHttp
-dontwarn okhttp3.internal.**
-keep class okhttp3.internal.** { *; }
-keepnames class okhttp3.internal.** { *; }

# OkHttp EventSource
-keep class okhttp3.internal.sse.** { *; }

####################################
# Cronet / Play Services Cronet    #
####################################

# Keep Cronet provider classes for runtime discovery
-keep class org.chromium.net.** { *; }
-keep class com.google.android.gms.net.** { *; }

####################################################
# Protobuf Lite (required by Cronet HttpFlagsLoader) #
####################################################

-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}

# Keep protobuf enums
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$ExtendableMessage {
    <fields>;
}

# Keep the default instance methods
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    public static *** getDefaultInstance();
    public static *** parser();
}
