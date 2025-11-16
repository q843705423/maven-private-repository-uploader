package com.maven.privateuploader.i18n

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 语言设置服务
 * 管理插件的语言设置
 */
@State(
    name = "LanguageSettings",
    storages = [Storage("LanguageSettings.xml")]
)
class LanguageSettings : PersistentStateComponent<LanguageSettings.State> {
    
    enum class Language(val code: String, val displayName: String) {
        CHINESE("zh", "中文"),
        ENGLISH("en", "English");
        
        override fun toString(): String {
            return displayName
        }
    }
    
    /**
     * 配置状态类
     */
    data class State(
        var language: String = Language.CHINESE.code
    )
    
    private var state = State()
    
    /**
     * 获取当前语言
     */
    var language: Language
        get() {
            return when (state.language) {
                Language.ENGLISH.code -> Language.ENGLISH
                else -> Language.CHINESE
            }
        }
        set(value) {
            state.language = value.code
        }
    
    override fun getState(): State {
        return state
    }
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }
    
    companion object {
        /**
         * 获取单例实例
         */
        @JvmStatic
        fun getInstance(): LanguageSettings {
            return ApplicationManager.getApplication().getService(LanguageSettings::class.java)
        }
    }
}

