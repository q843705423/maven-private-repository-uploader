# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an IntelliJ IDEA plugin called "Maven Private Repository Uploader" (version 2.1.0) that helps developers upload Maven dependencies from local repositories to private Maven repositories (Nexus, Artifactory, etc.) in offline/disconnected enterprise environments. The plugin has been significantly refactored with a new dependency analysis architecture based on the GavParserGroup system.

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

The plugin follows a layered architecture with clear separation of concerns and has been significantly refactored with a new dependency analysis system:

### Core Layers

**Action Layer** (`src/main/kotlin/com/maven/privateuploader/action/`)
- `UploadMavenDependenciesAction`: Main entry point triggered via context menu (ProjectViewPopupMenu, EditorPopupMenu), Tools menu, and Ctrl+Shift+M shortcut
- Validates project is Maven-enabled before opening upload dialog

**Service Layer** (`src/main/kotlin/com/maven/privateuploader/service/`)
- `DependencyUploadService`: Central orchestrator that coordinates the entire workflow with dedicated thread pools (10 threads for HEAD checks, 3 threads for uploads)
- `ExcelExportService`: Handles Excel export functionality with filtering options for missing dependencies
- Uses `GavParserGroup` for dependency analysis instead of the old `MavenDependencyAnalyzer`

**Analysis Layer** (`src/main/kotlin/com/maven/privateuploader/analyzer/`) - **COMPLETELY REFACTORED**
- `GavParserGroup`: New main dependency analyzer that replaces the old MavenDependencyAnalyzer
- `GavBatchParser`: Batch processing engine for parsing multiple POM files efficiently
- `GavCollector`: Collects and manages GAV (Group-Artifact-Version) coordinates during parsing
- `GavParser`: Core parser for individual POM files and dependency resolution
- `EffectivePomResolver`: Resolves effective POM with inheritance and dependency management
- `PomParserHelper`: Utility functions for POM file parsing and Maven model operations
- `MavenModelUtils`: Additional utilities for working with Maven models
- `MavenDependencyAnalyzer`: Legacy class now delegating to GavParserGroup
- `Env`: Environment configuration for local repository paths and settings
- `DependencyResolver`: Handles dependency resolution logic
- `PluginResolver`: Manages Maven plugin dependency resolution
- `YourModelResolver`: Custom ModelResolver implementation for Maven dependency resolution

**Client Layer** (`src/main/kotlin/com/maven/privateuploader/client/`)
- `PrivateRepositoryClient`: HTTP client for communicating with private Maven repositories
- `UploadResult`: Data class for upload operation results
- Supports Basic Authentication and HEAD requests for existence checking
- Handles JAR, POM, and Sources JAR uploads using standard Maven deploy patterns

**Internationalization Layer** (`src/main/kotlin/com/maven/privateuploader/i18n`)
- `PrivateUploaderBundle`: Centralized internationalization support
- `LanguageSettings`: Manages language preferences (Chinese/English)
- Supports dynamic language switching with separate resource bundles

**Configuration Layer** (`src/main/kotlin/com/maven/privateuploader/config/`, `src/main/kotlin/com/maven/privateuploader/state/`)
- `PrivateRepoConfigurable`: Settings UI in File → Settings → Tools → "Maven私仓上传"
- `PrivateRepoSettings`: Persistent configuration management using IntelliJ's configuration system
- Stores repository URL, credentials, and enablement status

**UI Layer** (`src/main/kotlin/com/maven/privateuploader/ui/`)
- `DependencyUploadDialog`: Main dialog for dependency selection and upload management
- `UploadProgressDialog`: Progress tracking with real-time updates and log display
- `ErrorDetailDialog`: Detailed error reporting dialog
- `DependencyTableModel`: Table model with custom cell rendering for status indicators
- `DependencyTableColumn`: Column definitions and configuration for the dependency table

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

**Analysis Data Models** (`src/main/kotlin/com/maven/privateuploader/analyzer/`)
- `Gav`: Immutable data class representing Group-Artifact-Version coordinates
- `ArtifactCoordinate`: Extended coordinate system with additional metadata
- `MavenProjectArtifactCollector`: Collects artifacts from Maven projects
- `MavenModuleScanner`: Scans Maven modules for dependencies

## Plugin Configuration

**plugin.xml** (`src/main/resources/META-INF/plugin.xml`)
- **Plugin ID**: `com.maven.privateuploader`
- **Dependencies**: `com.intellij.modules.platform`, `org.jetbrains.idea.maven`
- **Actions**: Integrated into ProjectViewPopupMenu, EditorPopupMenu, and Tools menu
- **Settings**: Application-level configurable under Tools category
- **Target Platform**: IntelliJ IDEA 2024.2.5 (build range 242-252.*)

## Key Integration Points

### Maven Integration - **COMPLETELY REFACTORED**
- **NEW**: Uses Maven model-builder (3.9.6) for robust POM parsing and dependency resolution
- **NEW**: Implements custom ModelResolver for proper dependency resolution chain
- **NEW**: Supports recursive POM parsing with parent POM inheritance
- **NEW**: BOM dependency parsing support for dependency management
- Leverages bundled Maven plugin (`org.jetbrains.idea.maven`)
- Uses `GavParserGroup` instead of direct `MavenProjectsManager` access
- Handles multi-module projects with batch processing capabilities
- Enhanced support for complex Maven project structures

### HTTP Communication
- Uses OkHttp 4.12.0 for repository communication with logging interceptor
- Implements Basic Authentication via request interceptors
- Follows standard Maven repository URL patterns for different file types
- Enhanced error handling and detailed upload result reporting

### UI Integration
- **NEW**: Comprehensive error detail dialog for better debugging
- **NEW**: Enhanced dependency table with configurable columns
- **NEW**: Excel export functionality with filtering for missing dependencies
- **NEW**: Internationalization support with Chinese/English language switching
- Custom table cell rendering for dependency status
- Progress tracking with cancellation support and real-time log display
- Configuration validation and comprehensive error handling

### Internationalization Support
- **NEW**: Dynamic language switching between Chinese and English
- Separate resource bundles for different languages
- Centralized message management through `PrivateUploaderBundle`
- Language persistence in IDE settings

## Development Notes

### Running the Plugin
Use `./gradlew runIde` to launch a development IDEA instance with the plugin loaded. This is the primary way to test functionality during development.

### Testing Strategy
- Unit tests are located in `src/test/kotlin/com/maven/privateuploader/`
- **NEW**: Enhanced test coverage with `PomParserRecursiveParentTest` for recursive POM parsing
- Uses JUnit 4 with OpenTest4J for better test failure reporting
- `DependencyInfoTest.kt` contains tests for the core data model
- **NEW**: Tests for the refactored dependency analysis system

### Configuration Management
Settings are persisted using IntelliJ's `PersistentStateComponent` system and stored in the IDE's configuration files. The configuration includes repository URL, credentials, and feature enablement status.

### Error Handling
The plugin implements comprehensive error handling at each layer:
- **NEW**: Detailed error reporting with `ErrorDetailDialog` for debugging
- **NEW**: Enhanced exception handling in the refactored dependency analysis system
- Service layer catches and logs exceptions during analysis and upload with dedicated thread pools
- UI layer displays user-friendly error messages with internationalization support
- **NEW**: Graceful handling of POM parsing errors and model building exceptions
- Configuration validation ensures required fields before enabling features

## Recent Major Changes (Refactoring)

### Dependency Analysis System Overhaul
- **Complete replacement** of the original `MavenDependencyAnalyzer` with `GavParserGroup`
- **New Maven model-builder integration** using official Maven libraries (3.9.6)
- **Enhanced POM parsing** with proper parent POM inheritance and dependency management support
- **Batch processing capabilities** for handling multiple POM files efficiently
- **Improved error handling** and logging throughout the dependency analysis pipeline
- **Thread pool optimization** with separate pools for checking (10 threads) and uploading (3 threads)

### UI and UX Enhancements
- **Added internationalization support** with Chinese/English language switching
- **Excel export functionality** with filtering for missing dependencies
- **Enhanced error dialog** with detailed error information and troubleshooting
- **Improved dependency table** with configurable columns and better cell rendering

### Dependencies and Libraries
- **Added Apache POI** (5.2.5) for Excel export functionality
- **Added Maven model-builder** (3.9.6) for robust POM parsing
- **Enhanced HTTP client** with logging interceptor for better debugging

## File Structure Highlights

- **`src/main/kotlin/com/maven/privateuploader/`** - All main source code organized by functional layer
- **`src/main/kotlin/com/maven/privateuploader/analyzer/`** - **NEW**: Completely refactored dependency analysis system
- **`src/main/kotlin/com/maven/privateuploader/service/`** - Enhanced services with new features
- **`src/main/kotlin/com/maven/privateuploader/ui/`** - Improved UI components with new dialogs and tables
- **`src/main/kotlin/com/maven/privateuploader/i18n/`** - **NEW**: Internationalization support
- **`src/main/resources/messages/`** - Internationalization bundles (Chinese and English)
- **`src/main/resources/icons/`** - Plugin icons
- **`src/test/kotlin/com/maven/privateuploader/`** - Enhanced unit tests including new analysis system tests
- **`.github/workflows/`** - CI/CD pipeline for automated testing and verification

## Development Workflow Rules

### Compilation Requirements
- **MANDATORY**: After every code change, run `./gradlew compileKotlin`
- **FIX IMMEDIATELY**: If compilation fails, fix the issues right away
- **REPEAT**: Continue compiling until all errors are resolved
- **ENSURE**: All code changes must maintain compilability

### Git Commit Requirements
- **PER-FEATURE**: Commit code after completing each requirement/feature
- **ATOMIC**: Each commit should represent a complete, functional change
- **TRACKABLE**: Ensure commit history reflects meaningful development progress

### Workflow Sequence
1. Make code changes
2. Run `./gradlew compileKotlin`
3. Fix any compilation errors
4. Repeat compilation until successful
5. Complete the requirement/feature
6. Git commit the changes
7. Move to next requirement