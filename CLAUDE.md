# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an IntelliJ IDEA plugin called "Maven Private Repository Uploader" (version 2.1.0) that helps developers upload Maven dependencies from local repositories to private Maven repositories (Nexus, Artifactory, etc.) in offline/disconnected enterprise environments.

## Build Commands

```bash
# Development and Testing
./gradlew runIde                    # Run IDEA with plugin for development/debugging
./gradlew test                     # Run unit tests
./gradlew check                    # Run all checks (tests + verification)
./gradlew build                    # Build the plugin
./gradlew buildPlugin              # Build plugin distribution package
./gradlew runIdeForUiTests         # Run IDE for UI testing with robot server

# Plugin Verification
./gradlew verifyPlugin             # Verify plugin compatibility with target IDE versions

# Publishing and Release
./gradlew publishPlugin            # Publish to JetBrains Marketplace (requires environment variables)
./gradlew patchChangelog           # Update changelog for new version

# Single Test Execution
./gradlew test --tests "com.maven.privateuploader.DependencyInfoTest"  # Run specific test class
```

## Architecture Overview

The plugin follows a layered architecture with clear separation of concerns:

### Core Layers

**Action Layer** (`src/main/kotlin/com/maven/privateuploader/action/`)
- `UploadMavenDependenciesAction`: Main entry point triggered via context menu (ProjectViewPopupMenu, EditorPopupMenu), Tools menu, and Ctrl+Shift+M shortcut
- Validates project is Maven-enabled before opening upload dialog

**Service Layer** (`src/main/kotlin/com/maven/privateuploader/service/`)
- `DependencyUploadService`: Central orchestrator that coordinates the entire workflow
- Handles dependency analysis, repository pre-checking, and upload processes
- Registered as project-level service

**Analysis Layer** (`src/main/kotlin/com/maven/privateuploader/analyzer/`)
- `MavenDependencyAnalyzer`: Analyzes Maven projects to extract dependencies
- Processes multi-module projects and filters out project artifacts
- Uses `MavenProjectsManager` to access Maven project data

**Client Layer** (`src/main/kotlin/com/maven/privateuploader/client/`)
- `PrivateRepositoryClient`: HTTP client for communicating with private Maven repositories
- Supports Basic Authentication and HEAD requests for existence checking
- Handles JAR, POM, and Sources JAR uploads using standard Maven deploy patterns

**Configuration Layer** (`src/main/kotlin/com/maven/privateuploader/config/`, `src/main/kotlin/com/maven/privateuploader/state/`)
- `PrivateRepoConfigurable`: Settings UI in File → Settings → Tools → "Maven私仓上传"
- `PrivateRepoSettings`: Persistent configuration management using IntelliJ's configuration system
- Stores repository URL, credentials, and enablement status

**UI Layer** (`src/main/kotlin/com/maven/privateuploader/ui/`)
- `DependencyUploadDialog`: Main dialog for dependency selection and upload management
- `UploadProgressDialog`: Progress tracking with real-time updates and log display
- `DependencyTableModel`: Table model with custom cell rendering for status indicators

### Data Models

**`DependencyInfo.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- Represents Maven artifacts with GAV coordinates, local paths, and repository status
- Tracks `CheckStatus` (EXISTS, MISSING, ERROR, CHECKING, UNKNOWN)
- Provides equality/hashCode for deduplication and GAV string formatting

**`RepositoryConfig.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- Encapsulates private repository configuration
- Includes URL building utilities for different repository layouts (Nexus, Artifactory)
- Provides validation logic for configuration completeness

**`CheckStatus.kt`** (`src/main/kotlin/com/maven/privateuploader/model/`)
- Enum for dependency existence status in private repositories

## Plugin Configuration

**plugin.xml** (`src/main/resources/META-INF/plugin.xml`)
- **Plugin ID**: `com.maven.privateuploader`
- **Dependencies**: `com.intellij.modules.platform`, `org.jetbrains.idea.maven`
- **Actions**: Integrated into ProjectViewPopupMenu, EditorPopupMenu, and Tools menu
- **Settings**: Application-level configurable under Tools category
- **Target Platform**: IntelliJ IDEA 2024.2.5 (build range 242-252.*)

## Key Integration Points

### Maven Integration
- Leverages bundled Maven plugin (`org.jetbrains.idea.maven`)
- Uses `MavenProjectsManager` to access project structure and dependencies
- Handles multi-module projects and artifact filtering

### HTTP Communication
- Uses OkHttp 4.12.0 for repository communication
- Implements Basic Authentication via request interceptors
- Follows standard Maven repository URL patterns for different file types

### UI Integration
- Custom table cell rendering for dependency status
- Progress tracking with cancellation support
- Configuration validation and error handling

## Development Notes

### Running the Plugin
Use `./gradlew runIde` to launch a development IDEA instance with the plugin loaded. This is the primary way to test functionality during development.

### Testing Strategy
- Unit tests are located in `src/test/kotlin/com/maven/privateuploader/`
- Uses JUnit 4 with OpenTest4J for better test failure reporting
- `DependencyInfoTest.kt` contains tests for the core data model

### Configuration Management
Settings are persisted using IntelliJ's `PersistentStateComponent` system and stored in the IDE's configuration files. The configuration includes repository URL, credentials, and feature enablement status.

### Error Handling
The plugin implements comprehensive error handling at each layer:
- Service layer catches and logs exceptions during analysis and upload
- UI layer displays user-friendly error messages
- Configuration validation ensures required fields before enabling features

## File Structure Highlights

- **`src/main/kotlin/com/maven/privateuploader/`** - All main source code organized by functional layer
- **`src/main/resources/messages/`** - Internationalization bundles (Chinese)
- **`src/main/resources/icons/`** - Plugin icons
- **`src/test/kotlin/com/maven/privateuploader/`** - Unit tests
- **`.github/workflows/`** - CI/CD pipeline for automated testing and verification