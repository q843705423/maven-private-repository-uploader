package com.maven.privateuploader.i18n

import com.intellij.AbstractBundle
import com.intellij.openapi.util.NlsContexts
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * 国际化资源包
 * 根据用户设置的语言返回对应的文本
 */
class PrivateUploaderBundle {
    companion object {
        @NonNls
        private const val BUNDLE = "messages.PrivateUploaderBundle"
        
        private val defaultBundle = object : AbstractBundle(BUNDLE) {}
        
        /**
         * 获取本地化消息
         */
        @JvmStatic
        fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
            val language = LanguageSettings.getInstance().language
            val bundleName = if (language == LanguageSettings.Language.ENGLISH) {
                "${BUNDLE}_en"
            } else {
                BUNDLE
            }
            
            return try {
                // 使用类加载器加载资源
                val classLoader = PrivateUploaderBundle::class.java.classLoader
                val resourceBundle = java.util.ResourceBundle.getBundle(bundleName, java.util.Locale.getDefault(), classLoader)
                var message = resourceBundle.getString(key)
                // 替换参数 {0}, {1}, ...
                params.forEachIndexed { index, param ->
                    message = message.replace("{$index}", param.toString())
                }
                message
            } catch (e: Exception) {
                // 如果找不到资源，尝试使用默认的 bundle
                try {
                    defaultBundle.getMessage(key, *params)
                } catch (e2: Exception) {
                    key // 如果都找不到，返回 key
                }
            }
        }
        
        /**
         * 获取配置名称（用于 @NlsContexts）
         */
        @JvmStatic
        @NlsContexts.ConfigurableName
        fun configName(@PropertyKey(resourceBundle = BUNDLE) key: String): String {
            return message(key)
        }
        
        /**
         * 获取对话框标题（用于 @NlsContexts）
         */
        @JvmStatic
        @NlsContexts.DialogTitle
        fun dialogTitle(@PropertyKey(resourceBundle = BUNDLE) key: String): String {
            return message(key)
        }
    }
}

