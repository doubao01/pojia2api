# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class io.modelbridge.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SPI 实现类通过反射或注册表使用，不能混淆
-keep class io.modelbridge.providers.builtin.** { *; }
-keep class io.modelbridge.providers.OpenAiCompatibleProvider { *; }

# 保留行号，便于排查线上崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
