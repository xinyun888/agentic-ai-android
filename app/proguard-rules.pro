# ===== Gson 反射序列化的 data class（字段名混淆会导致序列化/反序列化失败）=====
-keep class com.example.aichat.data.** { *; }
-keep class com.example.aichat.viewmodel.AgentStep { *; }
-keep class com.example.aichat.viewmodel.ChatViewModel$AgentState { *; }
-keep class com.example.aichat.viewmodel.ChatViewModel$PreviewItem { *; }

# Gson 泛型签名（TypeToken 依赖）与注解。
# InnerClasses/EnclosingMethod 是 Signature 生效的前提
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
# 防 R8 剥离 @SerializedName 字段
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ===== 四大组件（Manifest 注册，防 R8 误删）=====
-keep class com.example.aichat.MainActivity { *; }
-keep class com.example.aichat.service.ActiveModeService { *; }
-keep class com.example.aichat.service.ScreenControlService { *; }

# Gson 序列化的嵌套类（ActiveModeService 的陪伴配置，存 active_mode SharedPreferences）
-keep class com.example.aichat.service.ActiveModeService$ActiveConfig { *; }
