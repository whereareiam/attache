# Attache

A runtime dependency management library for Java applications, particularly suited for Minecraft server plugins and
standalone applications.

## Overview

Attache allows you to download, cache, relocate, and load dependencies at runtime. This is particularly useful for:

- Reducing plugin file sizes by not bundling dependencies
- Avoiding conflicts between different versions of the same library
- Managing dependencies dynamically

## Features

- **Runtime Dependency Loading**: Download and load Maven artifacts at runtime
- **Relocation Support**: Relocate packages to avoid conflicts using jar-relocator
- **Multiple Platforms**: Support for standalone Java applications, Bukkit-family plugins, BungeeCord plugins, and Velocity plugins
- **Repository Management**: Support for Maven Central, Maven Local, custom repositories, and more
- **Isolated Class Loading**: Load libraries in isolated class loaders when needed
- **Checksum Verification**: Verify downloaded libraries with SHA-256 checksums

## Installation

### Gradle

Add the repository:

```gradle
repositories {
    maven("https://maven.whereareiam.me/release")
}
```

Add the dependency for your platform:

**For Standalone Applications:**

```gradle
dependencies {
    implementation("me.whereareiam:attache-standalone:VERSION")
}
```

**For Spring Boot (auto-configures a StandaloneLibraryManager):**

```gradle
dependencies {
	implementation("me.whereareiam:attache-spring:VERSION")
}
```

**For Paper Plugins:**

```gradle
dependencies {
    implementation("me.whereareiam:attache-paper:VERSION")
}
```

**For BungeeCord Plugins:**

```gradle
dependencies {
    implementation("me.whereareiam:attache-bungeecord:VERSION")
}
```

**Remember to shade and relocate Attache to avoid conflicts:**

```gradle
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

shadowJar {
    relocate("me.whereareiam.attache", "your.package.libs.attache")
}
```

### Gradle Plugin

Attache can also generate runtime descriptor fragments directly from your build.

```gradle
plugins {
    id("me.whereareiam.attache") version "VERSION"
}
```

Declare Attache-managed libraries through dependency buckets:

```kotlin
dependencies {
    attache(libs.guice)
    attache(libs.configura)
    attacheOnly(libs.jedis)
}
```

Use the `attache {}` block only for Attache-specific metadata:

```kotlin
attache {
    transitive.set(true)

    mavenLocal()
    repository("https://maven.whereareiam.me/release")
    repository("https://maven.whereareiam.me/development")

    library(libs.guice) {
        relocate("com{}google{}inject", "me.whereareiam.identica.library.guice")
        relocate("com{}google{}common", "me.whereareiam.identica.library.guava")
    }

    library(libs.jedis) {
        transitive.set(false)
    }
}
```

`excludeTransitive` leaves an artifact out of a library's transitive dependencies, as a Maven exclusion does: the excluded artifact is not downloaded and neither are the dependencies it brings in. A dependency of the excluded artifact is still downloaded when another dependency of the library needs it too. Exclude an artifact your application already ships in another version, so its own dependencies do not arrive next to yours:

```kotlin
attache {
    library(libs.strata.adapter.configura) {
        excludeTransitive("me.whereareiam", "configura")
    }
}
```

`relocate` directly in the `attache {}` block relocates a package in every library the application's descriptors declare, including those other projects of the build declare, and in their transitive dependencies. Use it for a package the application shades and relocates itself, so every downloaded library refers to the shaded classes by their relocated name. A library's own relocation of the same package takes precedence, and two descriptors may not relocate one package to different names:

```kotlin
attache {
    relocate("net{}kyori{}adventure", "me.example.libs.adventure")
}
```

Libraries that only some installations need, such as the driver of one database, go in a named group. A grouped library is downloaded only when the application asks for its group at runtime, and declaring it in a group also adds it as a dependency:

```kotlin
attache {
    group("postgres") {
        library(libs.postgresql)
    }

    group("sqlite") {
        library(libs.sqlite.jdbc)
    }
}
```

The plugin writes one descriptor fragment per Gradle project to a path derived from the project's directory relative to the repository root:

```text
META-INF/attache/<project-path>/attache.xml
```

Examples:

```text
project(":identica-common")
-> META-INF/attache/identica-common/attache.xml

project(":identica-provider:provider-premium:premium")
-> META-INF/attache/identica-provider/provider-premium/premium/attache.xml
```

At runtime, Attache managers can scan `META-INF/attache/**/attache.xml`, merge all discovered fragments, and load the declared libraries when `loadDescriptors()` is called.

## Usage

### Basic Example (Standalone)

```java
import me.whereareiam.attache.model.Library;
import me.whereareiam.attache.Repositories;
import me.whereareiam.attache.platform.standalone.StandaloneLibraryManager;
import me.whereareiam.attache.common.logging.adapter.JDKLoggingHelper;

import java.nio.file.Paths;
import java.util.logging.Logger;

public class Example {
	public static void main(String[] args) {
		// Create library manager
		StandaloneLibraryManager libraryManager = new StandaloneLibraryManager(
				new JDKLogAdapter(Logger.getLogger("Attache")),
				Paths.get(".")
		);

		// Add Maven Central
		libraryManager.addMavenCentral();

		// Define and load a library
		Library library = Library.builder()
				.groupId("com{}google{}code{}gson")  // "{}" is replaced with "."
				.artifactId("gson")
				.version("2.10.1")
				.relocate("com{}google{}gson", "your{}package{}libs{}gson")
				.build();

		// Load the library
		libraryManager.loadLibrary(library);
	}
}
```

### Paper Plugin Example

```java
import me.whereareiam.attache.model.Library;
import me.whereareiam.attache.platform.paper.PaperLibraryManager;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {
	@Override
	public void onEnable() {
		// Create library manager
		PaperLibraryManager libraryManager = new PaperLibraryManager(this);

		// Add repositories
		libraryManager.addMavenLocal();
		libraryManager.addMavenCentral();

		// Load dependencies
		Library library = Library.builder()
				.groupId("org{}slf4j")
				.artifactId("slf4j-api")
				.version("2.0.9")
				.relocate("org{}slf4j", "your{}plugin{}libs{}slf4j")
				.checksum("7a9b7c9d2c1a0e8a4b7f1c9d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f")
				.build();

		libraryManager.loadLibrary(library);
	}
}
```

### BungeeCord Plugin Example

```java
import me.whereareiam.attache.platform.bungeecord.BungeeCordLibraryManager;
import net.md_5.bungee.api.plugin.Plugin;

public final class MyProxyPlugin extends Plugin {
	@Override
	public void onEnable() {
		BungeeCordLibraryManager libraryManager = new BungeeCordLibraryManager(this);
		libraryManager.addMavenCentral();
		libraryManager.loadDescriptors();
	}
}
```

### Zero-Config Runtime Loading

When your jar contains Gradle-generated Attache descriptors, trigger descriptor loading explicitly after creating the manager:

```java
VelocityLibraryManager libraryManager = new VelocityLibraryManager(proxyServer, pluginContainer, logger, dataPath, ".libraries");
libraryManager.loadDescriptors();
```

Calling `loadDescriptors()` will:

- discover all `META-INF/attache/**/attache.xml` fragments on the classpath
- merge repositories, relocations and library definitions
- resolve the transitive dependencies of the libraries declared outside any group as one dependency graph
- download and load those libraries and their dependencies

One graph means every artifact loads in one version, as Maven resolves a project: the version nearest to a declared library wins, and a declared library wins over any other version of itself, so declaring a library pins its version. A dependency still loads the way each library that needs it loads, with that library's relocations and class loader, so it may load more than once, relocated differently. Two libraries that resolve transitive dependencies may not declare different versions of one artifact. Each `loadGroups` call resolves the libraries of its groups as one graph of their own.

`loadDescriptors()` leaves out libraries declared in a group. Load a group once you know it is needed, for example after reading which database is configured:

```java
libraryManager.loadDescriptors();
libraryManager.loadGroups(config.databaseType());
```

`loadGroups` skips libraries that are already loaded and throws `IllegalArgumentException` for a group that no descriptor declares.

A manager reads the descriptors of its class loader, the class loader's parents, the thread's context class loader and its own jar. A part of an application with a class loader of its own, such as an add-on loaded in a child class loader, would read the application's descriptors as well and load its libraries again. Give that part's manager its own jar as the only source before loading:

```java
LibraryManager addonLibraries = new StandaloneLibraryManager(logging, dataDirectory, "libraries", addonClassLoader);
addonLibraries.setDescriptorSources(List.of(addonJar.toUri().toURL()));
addonLibraries.loadDescriptors();
```

Libraries the application's class loader already provides are still skipped where the request allows it.

For BungeeCord plugins, avoid declaring the same artifacts in Bungee's native `libraries` block and in Attache-managed descriptors at the same time. Let one system own each dependency to avoid duplicate loading and version ambiguity.

### Spring Boot Example (auto-configuration)

Configure in `application.yml`:

```yaml
attache:
  library-path: ".libraries" # optional; defaults to .libraries relative to working dir
  repositories:
    - https://repo.maven.apache.org/maven2/
  libraries:
    - groupId: org{}slf4j
      artifactId: slf4j-api
      version: 2.0.16
      isolated: true
```

Inject the managed `StandaloneLibraryManager` to add more libraries at runtime:

```java
import me.whereareiam.attache.model.Library;
import me.whereareiam.attache.platform.standalone.StandaloneLibraryManager;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ExtraLibrariesLoader {
	private final StandaloneLibraryManager attache;

	ExtraLibrariesLoader(StandaloneLibraryManager attache) {
		this.attache = attache;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void loadExtra() {
		attache.loadLibrary(
				Library.builder()
						.groupId("org{}apache{}commons")
						.artifactId("commons-lang3")
						.version("3.14.0")
						.build()
		);
	}
}
```

### Advanced Features

#### Custom Repositories

```java
libraryManager.addRepository("https://repo.example.com/maven/");
```

#### Maven Local

```java
libraryManager.addMavenLocal();
```

`addMavenLocal()` honors the `maven.repo.local` system property first and otherwise uses `~/.m2/repository`.

#### Isolated Class Loading

```java
Library library = Library.builder()
		.groupId("com{}example")
		.artifactId("library")
		.version("1.0.0")
		.isolatedLoad(true)
		.loaderId("my-isolated-loader")
		.build();
```

#### Fallback Repositories

```java
Library library = Library.builder()
		.groupId("com{}example")
		.artifactId("library")
		.version("1.0.0")
		.repository("https://primary-repo.com/")
		.fallbackRepository(Repositories.MAVEN_CENTRAL)
		.build();
```

#### Library Transformers

A `LibraryTransformer` changes a library jar after Attache downloaded and relocated it and before it is loaded,
for example to rewrite its bytecode for an older Java runtime. Attache does not know what a transformer does; it
runs the registered transformers in order and keeps their results in the save directory:

```java
libraryManager.registerLibraryTransformer(new LibraryTransformer() {
	@Override
	public @NotNull String getId() {
		return "downgrade";
	}

	@Override
	public @NotNull String getVersion() {
		return "1";
	}

	@Override
	public boolean appliesTo(@NotNull LibraryRequest library) {
		return !library.getGroupId().equals("me.example.tools");
	}

	@Override
	public @NotNull Path transform(@NotNull LibraryRequest library, @NotNull Path input, @NotNull Path output) throws IOException {
		if (!needsChange(input)) return input;

		rewrite(input, output);
		return output;
	}
});
libraryManager.loadDescriptors();
```

- A transformer writes the changed jar to `output` and returns it, or returns `input` when the jar needs no change.
- The transformed jar lies beside the downloaded one, named after the transformer's identifier and version. It is
  reused while the jar it was made from is unchanged, so a restart transforms nothing again; change the version
  whenever the transformer writes different bytes, and the libraries are transformed anew.
- Transformers apply to every library the manager downloads, isolated ones and the libraries Attache loads for
  itself, such as its relocator and resolver, so register them before loading libraries. A transformer that loads
  its own libraries through the same manager leaves them out in `appliesTo`.
- `loadLibrary(library, file)` loads the given file as it is.
- `getLibraryTransformers()` lists the registered transformers in order, so another manager can register the same ones.

## Credits

**Attache is based on [Libby](https://github.com/AlessioDP/libby) and [Libby Maven Resolver](https://github.com/AlessioDP/libby-maven-resolver)** by AlessioDP and Byteflux.

We created Attache because Libby is no longer actively maintained, and we needed a maintained version with
customizations. Special thanks to:

- **[AlessioDP](https://github.com/AlessioDP)** and **[Byteflux](https://github.com/Byteflux)** for creating Libby
- **[Luck](https://github.com/lucko)** for [LuckPerms](https://github.com/lucko/LuckPerms)
  and [jar-relocator](https://github.com/lucko/jar-relocator), which inspired the dependency management approach

## License

Attache is licensed under the MIT License, maintaining compatibility with the original Libby license.

## Building

```bash
./gradlew build
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
