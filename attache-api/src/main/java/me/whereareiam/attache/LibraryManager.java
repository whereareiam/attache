package me.whereareiam.attache;

import me.whereareiam.attache.type.Level;
import me.whereareiam.attache.type.ResolutionMode;
import me.whereareiam.attache.type.VerbosityMode;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.net.URL;
import java.util.Collection;
import java.util.List;

/**
 * A runtime dependency manager for java applications.
 * <p>
 * The library manager can resolve a dependency jar through the configured
 * Maven repositories, download it into a local cache, relocate it and then
 * load it into the classloader classpath.
 * <p>
 * Transitive dependencies for a library can be automatically resolved and downloaded
 * by setting resolveTransitiveDependencies in the adapted request. When enabled,
 * all transitive dependencies will be loaded before the main library.
 * <p>
 * It's recommended that libraries are relocated to prevent any namespace
 * conflicts with different versions of the same library bundled with other
 * java applications or maybe even bundled with the server itself.
 *
 */
@SuppressWarnings("unused")
public interface LibraryManager {
	/**
	 * Registers a library adapter for the given model class.
	 *
	 * @param type    the model class to adapt
	 * @param adapter the adapter implementation
	 * @param <T>     the model type
	 */
	<T> void registerLibraryAdapter(@NotNull Class<T> type, @NotNull LibraryAdapter<? super T> adapter);

	/**
	 * Removes a previously registered library adapter.
	 *
	 * @param type the model class
	 */
	void unregisterLibraryAdapter(@NotNull Class<?> type);

	/**
	 * Checks whether a library adapter exists for the given model type.
	 *
	 * @param type the model class
	 * @return true if an adapter is registered
	 */
	boolean hasLibraryAdapter(@NotNull Class<?> type);

	/**
	 * Registers a transformer that changes library jars after they are downloaded and relocated.
	 * <p>
	 * Transformers run in the order they were registered, on every library downloaded afterwards; register
	 * them before loading libraries. A transformer with the identifier of a registered one replaces it in
	 * its place. {@link #loadLibrary(Object, Path)} loads the given file as it is.
	 *
	 * @param transformer the transformer
	 * @see LibraryTransformer
	 */
	void registerLibraryTransformer(@NotNull LibraryTransformer transformer);

	/**
	 * Removes a registered library transformer.
	 *
	 * @param id the identifier of the transformer
	 */
	void unregisterLibraryTransformer(@NotNull String id);

	/**
	 * Checks whether a library transformer with the given identifier is registered.
	 *
	 * @param id the identifier of the transformer
	 * @return true if a transformer is registered under the identifier
	 */
	boolean hasLibraryTransformer(@NotNull String id);

	/**
	 * Gets the registered library transformers in the order they run.
	 * <p>
	 * A manager that should transform its libraries like this one, such as the manager of a part of an
	 * application, registers the same transformers:
	 * <pre>{@code
	 * libraryManager.getLibraryTransformers().forEach(partLibraries::registerLibraryTransformer);
	 * }</pre>
	 *
	 * @return an unmodifiable snapshot of the registered transformers
	 */
	@NotNull
	List<LibraryTransformer> getLibraryTransformers();

	/**
	 * Gets the configured log adapter.
	 *
	 * @return the log adapter
	 */
	@NotNull
	LoggingHelper getLoggingHelper();

	/**
	 * Sets the log level for this library manager.
	 *
	 * @param level the log level to set
	 */
	void setLogLevel(@NotNull Level level);

	/**
	 * Gets the current log level.
	 *
	 * @return the current log level
	 */
	@NotNull
	Level getLogLevel();

	/**
	 * Gets the directory where library jars are saved to.
	 *
	 * @return the save directory
	 */
	@NotNull
	Path getSaveDirectory();

	/**
	 * Adds a repository URL to this library manager.
	 * <p>
	 * Repositories are searched in the order they are added.
	 * <p>
	 * Common repository URLs can be found in the {@link Repositories} class as constants.
	 *
	 * @param url the repository URL
	 */
	void addRepository(@NotNull String url);

	/**
	 * Adds Maven Central repository.
	 */
	void addMavenCentral();

	/**
	 * Adds Sonatype OSS repository.
	 */
	void addSonatype();

	/**
	 * Adds JitPack repository.
	 */
	void addJitPack();

	/**
	 * Adds the current Maven local repository.
	 * <p>
	 * This method honors the {@code maven.repo.local} system property first and
	 * otherwise falls back to {@code ~/.m2/repository}.
	 */
	void addMavenLocal();

	/**
	 * Gets all configured repositories.
	 *
	 * @return collection of repository URLs
	 */
	@NotNull
	Collection<String> getRepositories();

	/**
	 * Downloads a library jar and caches it in the save directory if it doesn't already exist.
	 * <p>
	 * The returned jar has the library's relocations applied and has passed through the registered
	 * {@link LibraryTransformer}s.
	 *
	 * @param library the library model to download
	 * @return the path to the downloaded library jar
	 * @throws IOException              if an I/O error occurs
	 * @throws URISyntaxException       if the download URL is malformed
	 * @throws NoSuchAlgorithmException if SHA-256 algorithm is not available
	 */
	@NotNull
	<T> Path downloadLibrary(@NotNull T library) throws IOException, URISyntaxException, NoSuchAlgorithmException;

	/**
	 * Loads a library jar into the plugin's classpath.
	 *
	 * @param library the library model to load
	 * @param file    the path to the library jar
	 */
	<T> void loadLibrary(@NotNull T library, @NotNull Path file);

	/**
	 * Downloads and loads a library into the plugin's classpath.
	 *
	 * @param library the library model to download and load
	 */
	<T> void loadLibrary(@NotNull T library);

	/**
	 * Downloads and loads all provided libraries in parallel.
	 *
	 * @param libraries the library models to download and load
	 */
	<T> void loadLibraries(@NotNull T... libraries);

	/**
	 * Downloads and loads all provided libraries in parallel.
	 *
	 * @param libraries the library models to download and load
	 */
	<T> void loadLibraries(@NotNull Collection<? extends T> libraries);

	/**
	 * Sets the repository resolution mode.
	 *
	 * @param mode the resolution mode
	 */
	void setRepositoryResolutionMode(@NotNull ResolutionMode mode);

	/**
	 * Gets the current repository resolution mode.
	 *
	 * @return the current resolution mode
	 */
	@NotNull
	ResolutionMode getRepositoryResolutionMode();

	/**
	 * Sets the logging verbosity mode.
	 * <p>
	 * Controls how much information is logged during library operations.
	 *
	 * @param mode the verbosity mode
	 */
	void setVerbosityMode(@NotNull VerbosityMode mode);

	/**
	 * Gets the current logging verbosity mode.
	 *
	 * @return the current verbosity mode
	 */
	@NotNull
	VerbosityMode getVerbosityMode();

	/**
	 * Reads packaged Attache descriptors only from the given jars or directories.
	 * <p>
	 * By default a library manager reads the descriptors of its class loader and its parents, of the
	 * thread's context class loader and of the jar it was loaded from. A part of an application that
	 * has a class loader of its own, such as an add-on whose class loader is a child of the
	 * application's, would then read the application's descriptors too and load the application's
	 * libraries a second time. Naming the part's own jar keeps its library manager to the libraries
	 * that part declares:
	 * <pre>{@code
	 * LibraryManager addonLibraries = new StandaloneLibraryManager(logging, dataDirectory, "libraries", addonClassLoader);
	 * addonLibraries.setDescriptorSources(List.of(addonJar.toUri().toURL()));
	 * addonLibraries.loadDescriptors();
	 * }</pre>
	 * A library that a parent class loader already provides is still skipped when its request allows
	 * it, as without this setting.
	 *
	 * @param sources the jars or directories to read descriptors from; not empty
	 * @throws IllegalArgumentException if no source is given
	 * @throws IllegalStateException    if descriptors have already been read, by {@link #loadDescriptors()}
	 *                                  or {@link #loadGroups(String...)}
	 */
	void setDescriptorSources(@NotNull Collection<URL> sources);

	/**
	 * Loads packaged Attache descriptors and resolves the libraries they declare outside any group.
	 * <p>
	 * Libraries declared in a group are left out; load them with {@link #loadGroups(String...)}.
	 * The transitive dependencies of the libraries resolve as one dependency graph, so every artifact
	 * loads in one version; a dependency loads with the relocations and class loader of each library
	 * that needs it. Calling this method again has no effect.
	 */
	void loadDescriptors();

	/**
	 * Loads packaged Attache descriptors and resolves the libraries they declare in the given groups.
	 * <p>
	 * Groups let an application download only what its configuration needs, such as the driver of
	 * the database it connects to:
	 * <pre>{@code
	 * libraryManager.loadDescriptors();
	 * libraryManager.loadGroups(config.databaseType());
	 * }</pre>
	 * Libraries that are already loaded are skipped, so a group may be requested more than once and
	 * a library shared by several groups is loaded once. The libraries of one call resolve as one
	 * dependency graph, as {@link #loadDescriptors()} describes.
	 *
	 * @param groups the names of the groups to load
	 * @throws IllegalArgumentException if no packaged descriptor declares one of the groups
	 */
	void loadGroups(@NotNull String... groups);

	/**
	 * Prints a summary of all loaded libraries.
	 * <p>
	 * Useful when using SUMMARY or QUIET verbosity modes.
	 */
	void printLoadedLibrariesSummary();
}
