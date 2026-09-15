# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# KeyConfigHelper - keep classes and fields for Gson
-keep class com.limelight.utils.KeyConfigHelper {*;}
-keep class com.limelight.utils.KeyConfigHelper$ShortcutFile {*;}
-keep class com.limelight.utils.KeyConfigHelper$Shortcut {*;}

# Keep TensorFlow Lite GPU delegate classes that R8 might incorrectly remove
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.opencv.** { *; }

# Profiles
-keep class com.limelight.profiles.ProfilesManager$ProfilesData {*;}
-keep class com.limelight.profiles.SettingsProfile {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# Leia's CNSDK, used by the leia flavour. Most of it is reached from libleiaSDK.so through
# JNI rather than from Java, and R8 cannot see those references: left to itself it strips
# the head tracking and interlacing classes as unused and the SDK crashes as soon as native
# code looks for them. Keep the lot, members included, since JNI resolves by name.
# Harmless in the lenticular flavour, which has no com.leia classes to keep.
-keep class com.leia.** { *; }
-keepclassmembers class com.leia.** { *; }
-dontwarn com.leia.**
# Leia's media SDK, used by the leia flavour for 2D-to-3D conversion. These are declarations
# only -- the implementation lives in the device's com.leiainc.media.service APK and is
# loaded into this process at runtime through DexClassLoader. That loader's parent is this
# app's own, so class resolution is parent-first and the service's code binds to the
# interfaces kept here. It finds them by name, which R8 cannot see, so renaming any of them
# leaves the implementation unable to resolve what it was compiled against.
#
# The loader class itself has to keep its name too: model code inside the service calls back
# to LeiaMediaSDK.getAppWrapper() to find out where its native libraries live.
-keep class com.leiainc.** { *; }
-keepclassmembers class com.leiainc.** { *; }
-dontwarn com.leiainc.**
