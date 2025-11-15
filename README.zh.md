# Maven Private Repository Uploader - IntelliJ IDEA 插件

一个功能强大的 IntelliJ IDEA 插件，用于将 Maven 依赖从本地仓库上传到私有 Maven 仓库（Nexus、Artifactory 等）。

## 功能特性

### 核心功能
- **依赖分析**: 自动分析 Maven 项目的所有依赖（包括传递依赖）
- **存在性检查**: 通过 REST 接口检查依赖在私仓中的存在性
- **批量上传**: 一键上传缺失的依赖到 Maven 私仓
- **进度跟踪**: 实时显示上传进度和详细日志
- **多平台支持**: 兼容主流 Maven 私仓（Nexus、Artifactory 等）

### 使用场景
专为断网环境下的企业开发者设计，解决将本地 Maven 依赖"搬运"到私有 Maven 仓库的问题。

### 快捷操作
- **快捷键**: `Ctrl+Shift+M`
- **右键菜单**: 项目视图和编辑器右键菜单
- **工具菜单**: Tools 菜单集成

## 安装方式

### 从源码构建
```bash
# 克隆项目
git clone https://github.com/your-org/maven-private-repository-uploader.git
cd maven-private-repository-uploader

# 构建插件
./gradlew buildPlugin

# 运行开发环境
./gradlew runIde
```

### 从 JetBrains Marketplace 安装
1. 打开 IntelliJ IDEA
2. 进入 `File` → `Settings` → `Plugins`
3. 搜索 "Maven Private Repository Uploader"
4. 点击安装并重启 IDE

## 使用方法

### 配置私仓信息
1. 打开 `File` → `Settings` → `Tools` → "Maven私仓上传"
2. 配置以下信息：
   - **私仓URL**: 私有 Maven 仓库的地址
   - **用户名**: 认证用户名
   - **密码**: 认证密码

### 上传依赖
使用以下任一方式启动上传：
- 快捷键 `Ctrl+Shift+M`
- 项目右键菜单：`上传Maven依赖到私仓...`
- 工具菜单：`Tools` → `上传Maven依赖到私仓...`

### 操作步骤
1. 插件会自动分析当前 Maven 项目的所有依赖
2. 检查每个依赖在私仓中的存在状态
3. 显示需要上传的依赖列表
4. 选择需要上传的依赖后点击上传
5. 实时查看上传进度和日志

## 开发环境

### 构建命令
```bash
# 开发和测试
./gradlew runIde                    # 在开发环境中运行 IDEA
./gradlew test                     # 运行单元测试
./gradlew check                    # 运行所有检查
./gradlew build                    # 构建插件
./gradlew buildPlugin              # 构建插件分发包

# 插件验证
./gradlew verifyPlugin             # 验证插件与目标 IDE 版本的兼容性

# 发布和版本管理
./gradlew publishPlugin            # 发布到 JetBrains Marketplace
./gradlew patchChangelog           # 更新版本变更日志

# UI 测试
./gradlew runIdeForUiTests         # 启动带 robot 服务器的 UI 测试环境
```

### 单个测试执行
```bash
# 运行特定测试类
./gradlew test --tests "com.maven.privateuploader.DependencyInfoTest"
```

## 项目架构

### 核心层次

**操作层** (`src/main/kotlin/com/maven/privateuploader/action/`)
- `UploadMavenDependenciesAction`: 主要入口点，通过上下文菜单、编辑器菜单、工具菜单和 Ctrl+Shift+M 快捷键触发
- 在打开上传对话框前验证项目是否启用 Maven

**服务层** (`src/main/kotlin/com/maven/privateuploader/service/`)
- `DependencyUploadService`: 中央协调器，协调整个工作流程
- 处理依赖分析、仓库预检查和上传过程
- 注册为项目级服务

**分析层** (`src/main/kotlin/com/maven/privateuploader/analyzer/`)
- `MavenDependencyAnalyzer`: 分析 Maven 项目以提取依赖
- 处理多模块项目并过滤项目构件
- 使用 `MavenProjectsManager` 访问 Maven 项目数据

**客户端层** (`src/main/kotlin/com/maven/privateuploader/client/`)
- `PrivateRepositoryClient`: 与私有 Maven 仓库通信的 HTTP 客户端
- 支持基本认证和存在性检查的 HEAD 请求
- 使用标准 Maven 部署模式处理 JAR、POM 和源码 JAR 上传

**配置层** (`src/main/kotlin/com/maven/privateuploader/config/`, `src/main/kotlin/com/maven/privateuploader/state/`)
- `PrivateRepoConfigurable`: 设置界面（文件 → 设置 → 工具 → "Maven私仓上传"）
- `PrivateRepoSettings`: 使用 IntelliJ 配置系统的持久化配置管理
- 存储仓库 URL、凭据和启用状态

**界面层** (`src/main/kotlin/com/maven/privateuploader/ui/`)
- `DependencyUploadDialog`: 依赖选择和上传管理的主对话框
- `UploadProgressDialog`: 实时更新和日志显示的进度跟踪
- `DependencyTableModel`: 带有状态指示器的自定义单元格渲染的表格模型

### 数据模型

**`DependencyInfo.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- 表示带有 GAV 坐标、本地路径和仓库状态的 Maven 构件
- 跟踪 `CheckStatus`（EXISTS、MISSING、ERROR、CHECKING、UNKNOWN）
- 提供去重和 GAV 字符串格式化的相等性/哈希码

**`RepositoryConfig.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- 封装私有仓库配置
- 包含不同仓库布局（Nexus、Artifactory）的 URL 构建工具
- 提供配置完整性的验证逻辑

**`CheckStatus.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- 依赖在私有仓库中存在状态的枚举

## 插件配置

**plugin.xml** (`src/main/resources/META-INF/plugin.xml`)
- **插件 ID**: `com.maven.privateuploader`
- **依赖**: `com.intellij.modules.platform`、`org.jetbrains.idea.maven`
- **操作**: 集成到 ProjectViewPopupMenu、EditorPopupMenu 和工具菜单
- **设置**: 工具类别下的应用程序级可配置项
- **目标平台**: IntelliJ IDEA 2024.2.5（构建范围 242-252.*）

## 关键集成点

### Maven 集成
- 利用捆绑的 Maven 插件（`org.jetbrains.idea.maven`）
- 使用 `MavenProjectsManager` 访问项目结构和依赖
- 处理多模块项目和构件过滤

### HTTP 通信
- 使用 OkHttp 4.12.0 进行仓库通信
- 通过请求拦截器实现基本认证
- 遵循不同文件类型的标准 Maven 仓库 URL 模式

### UI 集成
- 依赖状态的自定义表格单元格渲染
- 带取消支持的进度跟踪
- 配置验证和错误处理

## 开发说明

### 运行插件
使用 `./gradlew runIde` 启动加载了插件的开发 IDEA 实例。这是开发期间测试功能的主要方式。

### 测试策略
- 单元测试位于 `src/test/kotlin/com/maven/privateuploader/`
- 使用带有 OpenTest4J 的 JUnit 4 以获得更好的测试失败报告
- `DependencyInfoTest.kt` 包含核心数据模型的测试

### 配置管理
设置使用 IntelliJ 的 `PersistentStateComponent` 系统持久化并存储在 IDE 的配置文件中。配置包括仓库 URL、凭据和功能启用状态。

### 错误处理
插件在每一层都实现了全面的错误处理：
- 服务层捕获并记录分析和上传期间的异常
- UI 层显示用户友好的错误消息
- 启用功能前的配置验证确保必填字段完整

## 文件结构概览

- **`src/main/kotlin/com/maven/privateuploader/`** - 按功能层组织的所有主要源代码
- **`src/main/resources/messages/`** - 国际化包（中文）
- **`src/main/resources/icons/`** - 插件图标
- **`src/test/kotlin/com/maven/privateuploader/`** - 单元测试
- **`.github/workflows/`** - 自动化测试和验证的 CI/CD 流水线

## 系统要求

- IntelliJ IDEA 2024.2.5 或更高版本
- Java 17 或更高版本
- Maven 项目

## 许可证

本插件采用 [MIT 许可证](LICENSE)。

## 支持与反馈

如果您遇到问题或有功能建议，请：
1. 创建 [GitHub Issue](https://github.com/your-org/maven-private-repository-uploader/issues)
2. 发送邮件至 support@your-company.com

## 更新日志

### 版本 2.1.0
- 初始版本发布
- 实现 Maven 依赖分析功能
- 实现私仓预检查功能
- 实现依赖上传功能
- 提供完整的 UI 界面

## 相关链接

- [IntelliJ Platform SDK 插件开发文档](https://plugins.jetbrains.com/docs/intellij)
- [IntelliJ Platform Gradle 插件文档](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [JetBrains Marketplace 质量指南](https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html)
- [Maven 仓库布局规范](https://maven.apache.org/repository/layout.html)
- [Nexus 仓库管理器](https://help.sonatype.com/repomanager3)
- [Artifactory 仓库管理器](https://jfrog.com/artifactory/)