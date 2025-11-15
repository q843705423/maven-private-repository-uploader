./gradlew runIde
这会启动一个带有你的插件的完整 IntelliJ IDEA 实例，非常适合开发和调试。

2. 验证插件兼容性

./gradlew verifyPlugin
这个命令会验证插件是否与指定版本的 IntelliJ IDEA 兼容。

3. 运行单元测试

./gradlew test
运行项目的单元测试。

4. UI测试

./gradlew runIdeForUiTests