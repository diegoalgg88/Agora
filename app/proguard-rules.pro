# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# JSch (SSH/SFTP)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Compose
-dontwarn androidx.compose.**

# JNI (llama): native code reads these classes' fields and constructors, and calls the
# callback methods, by name.
-keep class com.newoether.agora.api.NativeChatCallback { *; }
-keep class * implements com.newoether.agora.api.NativeChatCallback { *; }
-keep class com.newoether.agora.api.ChatTemplateToolCall { *; }
-keep class com.newoether.agora.api.ChatTemplateMessage { *; }
-keep class com.newoether.agora.api.ChatTemplateTool { *; }
-keep class com.newoether.agora.api.LlamaChatTemplateRequest { *; }
-keep class com.newoether.agora.api.ChatTemplateGrammarTrigger { *; }
-keep class com.newoether.agora.api.LlamaChatTemplateResult { *; }
-keepclasseswithmembernames class com.newoether.agora.** { native <methods>; }

# Reflection: flavor sandbox factories (AppContainer) and the fdroid-only flavor impls
# (SMS readers/senders, notification listener) loaded via Class.forName.
-keep class com.newoether.agora.sandbox.FdroidSandboxManagerFactory { <init>(...); }
-keep class com.newoether.agora.sandbox.PlaySandboxManagerFactory { <init>(...); }
-keep class com.newoether.agora.sms.SmsReaderImpl { *; }
-keep class com.newoether.agora.sms.SmsSenderImpl { *; }
-keep class com.newoether.agora.notifications.NotificationReaderImpl { *; }
-keep class com.newoether.agora.notifications.NotificationListenerControllerImpl { *; }

# LiteRT-LM: su JNI nativo (liblitertlm_jni.so) resuelve métodos y campos por nombre vía
# GetMethodID/GetFieldID sobre SamplerConfig, ThinkingConfig y las clases del paquete; R8 los
# renombró y nativeCreateConversation abortó con "JNI DETECTED ERROR: mid == null" (crash
# SIGABRT verificado on-device 2026-10-18, tid DefaultDispatch).
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Ktor server: JVM-only debug detection (DevelopmentMode), absent on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
