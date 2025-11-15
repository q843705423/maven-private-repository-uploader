package com.maven.privateuploader.state

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.maven.privateuploader.model.RepositoryConfig

/**
 * 私仓配置状态管理
 * 持久化保存配置信息到IDEA的配置文件中
 */
@State(
    name = "PrivateRepoSettings",
    storages = [Storage("PrivateRepoSettings.xml")]
)
class PrivateRepoSettings : PersistentStateComponent<PrivateRepoSettings.State> {

    /**
     * 配置状态类
     */
    data class State(
        var repositoryUrl: String = "",
        var username: String = "",
        var password: String = "",
        var repositoryId: String = "",
        var enabled: Boolean = false
    )

    private var state = State()

    /**
     * 获取配置对象
     */
    var config: RepositoryConfig
        get() = RepositoryConfig(
            repositoryUrl = state.repositoryUrl,
            username = state.username,
            password = state.password,
            repositoryId = state.repositoryId,
            enabled = state.enabled
        )
        set(value) {
            state.repositoryUrl = value.repositoryUrl
            state.username = value.username
            state.password = value.password
            state.repositoryId = value.repositoryId
            state.enabled = value.enabled
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
        fun getInstance(): PrivateRepoSettings {
            return ApplicationManager.getApplication().getService(PrivateRepoSettings::class.java)
        }

        /**
         * 重置为默认配置
         */
        fun resetToDefault() {
            val instance = getInstance()
            instance.state = State()
        }
    }
}