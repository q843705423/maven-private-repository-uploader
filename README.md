# Maven Private Repository Uploader - IntelliJ IDEA Plugin

A powerful IntelliJ IDEA plugin for uploading Maven dependencies from the local repository to private Maven repositories (Nexus, Artifactory, etc.).

## Features

### Core capabilities
- **Dependency analysis**: automatically analyses every dependency of the Maven project (including transitive ones)
- **Existence checks**: verifies dependency availability in the private repository through REST endpoints
- **Bulk uploads**: upload missing dependencies to the private Maven repository with a single click
- **Progress tracking**: real-time upload progress and detailed logs
- **Multi-platform support**: works with mainstream private Maven repositories (Nexus, Artifactory, etc.)

### Use cases
Designed for enterprise developers working in disconnected environments who need to "move" local Maven dependencies into a private Maven repository.

### Quick actions
- **Shortcut**: `Ctrl+Shift+M`
- **Context menu**: available from the Project view and editor
- **Tools menu**: integrated into the Tools menu

## Installation

### Build from source
```bash
# Clone the project
git clone https://github.com/your-org/maven-private-repository-uploader.git
cd maven-private-repository-uploader

# Build the plugin
./gradlew buildPlugin

# Run the development IDE
./gradlew runIde
```

### Install from JetBrains Marketplace
1. Open IntelliJ IDEA
2. Navigate to `File` → `Settings` → `Plugins`
3. Search for "Maven Private Repository Uploader"
4. Install the plugin and restart the IDE

## Usage

### Configure repository information
1. Open `File` → `Settings` → `Tools` → "Maven Private Repository Upload"
2. Configure the following fields:
   - **Repository URL**: address of the private Maven repository
   - **Username**: authentication username
   - **Password**: authentication password

### Upload dependencies
Trigger an upload using any of the following options:
- Shortcut `Ctrl+Shift+M`
- Project context menu: `Upload Maven Dependencies to Private Repo...`
- Tools menu: `Tools` → `Upload Maven Dependencies to Private Repo...`

### Workflow
1. The plugin analyses every dependency in the current Maven project
2. Each dependency is checked against the private repository
3. Missing dependencies are listed for review
4. Choose the artifacts that must be uploaded and start the upload
5. Monitor the progress and logs in real time

## Development environment

### Build commands
```bash
# Development and testing
./gradlew runIde                    # Run IDEA with the plugin in development mode
./gradlew test                     # Run unit tests
./gradlew check                    # Run all checks
./gradlew build                    # Build the plugin
./gradlew buildPlugin              # Build the distributable package

# Plugin verification
./gradlew verifyPlugin             # Verify compatibility with the target IDE versions

# Publishing and release management
./gradlew publishPlugin            # Publish to JetBrains Marketplace
./gradlew patchChangelog           # Update changelog entries

# UI tests
./gradlew runIdeForUiTests         # Launch UI test environment with the Robot Server
```

### Run a single test
```bash
# Execute a specific test class
./gradlew test --tests "com.maven.privateuploader.DependencyInfoTest"
```

## Architecture overview

### Core layers

**Actions** (`src/main/kotlin/com/maven/privateuploader/action/`)
- `UploadMavenDependenciesAction`: primary entry point, exposed through context menus, the Tools menu, and the Ctrl+Shift+M shortcut
- Validates that Maven is enabled for the project before opening the upload dialog

**Services** (`src/main/kotlin/com/maven/privateuploader/service/`)
- `DependencyUploadService`: orchestrates the end-to-end workflow
- Handles dependency analysis, repository pre-checks, and uploads
- Registered as a project-level service

**Analyzers** (`src/main/kotlin/com/maven/privateuploader/analyzer/`)
- `MavenDependencyAnalyzer`: extracts dependencies from Maven projects
- Handles multi-module projects and filters out project artifacts
- Uses `MavenProjectsManager` to access Maven project data

**Clients** (`src/main/kotlin/com/maven/privateuploader/client/`)
- `PrivateRepositoryClient`: HTTP client that communicates with the private Maven repository
- Supports basic authentication and HEAD requests for existence checks
- Uploads JARs, POMs, and source JARs following standard Maven deployment flows

**Configuration layer** (`src/main/kotlin/com/maven/privateuploader/config/`, `src/main/kotlin/com/maven/privateuploader/state/`)
- `PrivateRepoConfigurable`: settings panel (`File → Settings → Tools → "Maven Private Repository Upload"`)
- `PrivateRepoSettings`: persistent settings implementation using the IntelliJ configuration system
- Persists repository URLs, credentials, and enablement flags

**UI layer** (`src/main/kotlin/com/maven/privateuploader/ui/`)
- `DependencyUploadDialog`: main dialog for dependency selection and upload management
- `UploadProgressDialog`: progress view with real-time updates and logs
- `DependencyTableModel`: table model with custom cell rendering for status indicators

### Data models

**`DependencyInfo.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- Represents Maven artifacts with GAV coordinates, local paths, and repository status
- Tracks `CheckStatus` (EXISTS, MISSING, ERROR, CHECKING, UNKNOWN)
- Provides equality/hashCode implementations for deduplication and GAV formatting helpers

**`RepositoryConfig.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- Encapsulates private repository configuration
- Includes URL builders for different repository layouts (Nexus, Artifactory)
- Provides validation logic for configuration completeness

**`CheckStatus.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- Enumeration representing the repository state of a dependency

## Plugin configuration

**plugin.xml** (`src/main/resources/META-INF/plugin.xml`)
- **Plugin ID**: `com.maven.privateuploader`
- **Dependencies**: `com.intellij.modules.platform`, `org.jetbrains.idea.maven`
- **Actions**: integrated into ProjectViewPopupMenu, EditorPopupMenu, and the Tools menu
- **Settings**: application-level configurable under the Tools category
- **Target platform**: IntelliJ IDEA 2024.2.5 (build range 242-252.*)

## Key integration points

### Maven integration
- Relies on the bundled Maven plugin (`org.jetbrains.idea.maven`)
- Uses `MavenProjectsManager` to access project structure and dependencies
- Handles multi-module projects and artifact filtering

### HTTP communication
- Uses OkHttp 4.12.0 for repository communication
- Implements basic authentication via request interceptors
- Follows standard Maven repository URL patterns for different file types

### UI integration
- Custom table cell rendering for dependency states
- Progress tracking with cancellation support
- Configuration validation and error handling

## Development notes

### Running the plugin
Use `./gradlew runIde` to start a development instance of IntelliJ IDEA with the plugin loaded. This is the primary way to test features during development.

### Testing strategy
- Unit tests are located under `src/test/kotlin/com/maven/privateuploader/`
- Uses JUnit 4 with OpenTest4J for improved failure reporting
- `DependencyInfoTest.kt` contains tests for the core data model

### Configuration management
Settings are persisted using IntelliJ's `PersistentStateComponent` mechanism and stored in the IDE configuration directory. The configuration keeps repository URLs, credentials, and the feature enablement flag.

### Error handling
Every layer implements comprehensive error handling:
- Services capture and log exceptions thrown during analysis and upload
- UI surfaces user-friendly error messages
- Configuration validation ensures required fields are filled before enabling uploads

## File structure overview

- **`src/main/kotlin/com/maven/privateuploader/`** – primary sources organized by functional layer
- **`src/main/resources/messages/`** – localization bundles (Chinese)
- **`src/main/resources/icons/`** – plugin icons
- **`src/test/kotlin/com/maven/privateuploader/`** – unit tests
- **`.github/workflows/`** – CI/CD pipelines for automation, testing, and verification

## System requirements

- IntelliJ IDEA 2024.2.5 or newer
- Java 17 or newer
- Maven project

## License

Distributed under the [MIT License](LICENSE).

## Support and feedback

If you run into issues or want to suggest features:
1. Create a [GitHub Issue](https://github.com/your-org/maven-private-repository-uploader/issues)
2. Email support@your-company.com

## Changelog

### Version 2.1.0
- Initial release
- Maven dependency analysis
- Private repository pre-checks
- Dependency upload workflow
- Full UI experience

## Useful links

- [IntelliJ Platform SDK documentation](https://plugins.jetbrains.com/docs/intellij)
- [IntelliJ Platform Gradle Plugin documentation](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [JetBrains Marketplace Quality Guidelines](https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html)
- [Maven repository layout specification](https://maven.apache.org/repository/layout.html)
- [Nexus Repository Manager](https://help.sonatype.com/repomanager3)
- [Artifactory Repository Manager](https://jfrog.com/artifactory/)
