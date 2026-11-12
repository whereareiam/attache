package me.whereareiam.attache.common;

import me.whereareiam.attache.LibraryAdapter;
import me.whereareiam.attache.LibraryBatchLoader;
import me.whereareiam.attache.LibraryManager;
import me.whereareiam.attache.LibraryTransformer;
import me.whereareiam.attache.LoggingHelper;
import me.whereareiam.attache.Repositories;
import me.whereareiam.attache.common.classloader.IsolatedClassLoader;
import me.whereareiam.attache.common.descriptor.ClasspathDescriptorLoader;
import me.whereareiam.attache.common.descriptor.DescriptorCatalog;
import me.whereareiam.attache.common.loader.ParallelLibraryLoader;
import me.whereareiam.attache.common.loader.SequentialLibraryLoader;
import me.whereareiam.attache.common.logging.Logger;
import me.whereareiam.attache.resolution.DependencyResolver;
import me.whereareiam.attache.resolution.DependencyResolverFactory;
import me.whereareiam.attache.resolution.model.ResolvedArtifact;
import me.whereareiam.attache.resolution.model.ResolvedDependency;
import me.whereareiam.attache.common.util.LibraryHelper;
import me.whereareiam.attache.descriptor.AttacheDescriptorLibrary;
import me.whereareiam.attache.model.LibraryRequest;
import me.whereareiam.attache.model.RelocationRule;
import me.whereareiam.attache.type.Level;
import me.whereareiam.attache.type.LibraryLoadMode;
import me.whereareiam.attache.type.ResolutionMode;
import me.whereareiam.attache.type.VerbosityMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Base implementation of a runtime dependency manager.
 * Platform-specific implementations should extend this class and implement
 * the abstract methods.
 * <p>
 * This class implements {@link AutoCloseable} to properly clean up isolated classloaders.
 * Users should call {@link #close()} when done, or use try-with-resources.
 */
public abstract class BaseLibraryManager implements LibraryManager, AutoCloseable {
	protected static final String USER_AGENT = "Attache/1.0";

	/**
	 * Wrapped logger
	 */
	protected final Logger logger;

	/**
	 * Log adapter for the current platform
	 */
	protected final LoggingHelper loggingHelper;

	/**
	 * Directory where downloaded library jars are saved to
	 */
	protected final Path saveDirectory;

	/**
	 * Maven repositories used to resolve artifacts
	 */
	protected final Set<String> repositories = new LinkedHashSet<>();

	/**
	 * Lazily initialized helper for transitive dependencies resolution
	 */
	private @Nullable DependencyResolver dependencyResolver;
	private final @NotNull DependencyResolverFactory resolverFactory;

	/**
	 * Global isolated class loader for libraries
	 */
	protected final IsolatedClassLoader globalIsolatedClassLoader = new IsolatedClassLoader();

	/**
	 * Map of isolated class loaders and their IDs
	 */
	protected final Map<String, IsolatedClassLoader> isolatedLibraries = new HashMap<>();

	/**
	 * Repository resolution mode for libraries
	 */
	protected ResolutionMode resolutionMode = ResolutionMode.DEFAULT;

	/**
	 * Logging verbosity mode
	 */
	protected VerbosityMode verbosityMode = VerbosityMode.VERBOSE;

	/**
	 * Loaded libraries tracking for summary
	 */
	protected final List<LibraryRequest> loadedLibraries = new ArrayList<>();

	/**
	 * Failed libraries tracking for summary
	 */
	protected final Map<LibraryRequest, Exception> failedLibraries = new HashMap<>();

	/**
	 * Library adapter registry keyed by model type.
	 */
	protected final Map<Class<?>, LibraryAdapter<?>> libraryAdapters = new LinkedHashMap<>();

	private final ArtifactDownloadCoordinator artifactDownloadCoordinator;
	private final RelocationCoordinator relocationCoordinator;
	private final TransformationCoordinator transformationCoordinator;
	private final LibraryBatchLoader libraryBatchLoader;
	private final LibraryLoadMode libraryLoadMode;
	private final ClasspathDescriptorLoader classpathDescriptorLoader = new ClasspathDescriptorLoader();
	private final Set<String> loadedDescriptorLibraries = new HashSet<>();
	private DescriptorCatalog descriptorCatalog;
	@Nullable
	private List<URL> descriptorSources;
	private boolean defaultDescriptorLibrariesLoaded;

	/**
	 * Creates a library manager for direct dependencies. Supply a resolver factory
	 * through the other constructor when transitive resolution is needed.
	 *
	 * @param loggingHelper logging adapter
	 * @param dataDirectory data directory
	 * @param directoryName download directory name
	 */
	protected BaseLibraryManager(
			@NotNull LoggingHelper loggingHelper,
			@NotNull Path dataDirectory,
			@NotNull String directoryName
	) {
		this(loggingHelper, dataDirectory, directoryName, manager -> {
			throw new IllegalStateException("Transitive resolution requires a DependencyResolverFactory");
		});
	}

	/**
	 * Creates a manager with a lazily initialized transitive dependency resolver.
	 *
	 * @param loggingHelper logging adapter
	 * @param dataDirectory data directory
	 * @param directoryName download directory name
	 * @param resolverFactory factory called on the first transitive resolution
	 */
	protected BaseLibraryManager(
			@NotNull LoggingHelper loggingHelper,
			@NotNull Path dataDirectory,
			@NotNull String directoryName,
			@NotNull DependencyResolverFactory resolverFactory
	) {
		this.resolverFactory = resolverFactory;
		this.loggingHelper = requireNonNull(loggingHelper, "loggingHelper");
		this.saveDirectory = requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().resolve(requireNonNull(directoryName, "directoryName"));
		this.logger = new Logger(loggingHelper);
		this.artifactDownloadCoordinator = new ArtifactDownloadCoordinator(this, this.saveDirectory);
		this.relocationCoordinator = new RelocationCoordinator(this, this.saveDirectory);
		this.transformationCoordinator = new TransformationCoordinator(this, this.saveDirectory);
		this.libraryLoadMode = resolveLibraryLoadMode();
		this.libraryBatchLoader = createLibraryBatchLoader(this.libraryLoadMode);
		registerLibraryAdapter(LibraryRequest.class, request -> request);
	}

	/**
	 * Adds a file to the classloader classpath.
	 *
	 * @param file the file to add
	 */
	protected abstract void addToClasspath(@NotNull Path file);

	/**
	 * Adds a file to the isolated class loader.
	 * <p>
	 * Note: The IsolatedClassLoader instances are managed by this class and will be
	 * properly closed in the {@link #close()} method.
	 *
	 * @param library the library to add
	 * @param file    the file to add
	 */
	@SuppressWarnings("resource") // Classloaders are closed in close() method
	protected void addToIsolatedClasspath(@NotNull LibraryRequest library, @NotNull Path file) {
		IsolatedClassLoader classLoader;
		String loaderId = library.getLoader();
		if (loaderId != null) {
			classLoader = isolatedLibraries.computeIfAbsent(loaderId, s -> new IsolatedClassLoader());
		} else {
			classLoader = globalIsolatedClassLoader;
		}
		classLoader.addPath(file);
	}

	@Override
	@NotNull
	public LoggingHelper getLoggingHelper() {
		return loggingHelper;
	}

	@Override
	public void setLogLevel(@NotNull Level level) {
		logger.setLevel(level);
	}

	@Override
	@NotNull
	public Level getLogLevel() {
		return logger.getLevel();
	}

	@Override
	@NotNull
	public Path getSaveDirectory() {
		return saveDirectory;
	}

	@Override
	public <T> void registerLibraryAdapter(@NotNull Class<T> type, @NotNull LibraryAdapter<? super T> adapter) {
		requireNonNull(type, "type");
		requireNonNull(adapter, "adapter");
		synchronized (libraryAdapters) {
			libraryAdapters.put(type, adapter);
		}
	}

	@Override
	public void unregisterLibraryAdapter(@NotNull Class<?> type) {
		requireNonNull(type, "type");
		synchronized (libraryAdapters) {
			libraryAdapters.remove(type);
		}
	}

	@Override
	public boolean hasLibraryAdapter(@NotNull Class<?> type) {
		requireNonNull(type, "type");
		synchronized (libraryAdapters) {
			return libraryAdapters.containsKey(type);
		}
	}

	@Override
	public void registerLibraryTransformer(@NotNull LibraryTransformer transformer) {
		transformationCoordinator.register(transformer);
	}

	@Override
	public void unregisterLibraryTransformer(@NotNull String id) {
		transformationCoordinator.unregister(id);
	}

	@Override
	public boolean hasLibraryTransformer(@NotNull String id) {
		return transformationCoordinator.isRegistered(id);
	}

	@Override
	@NotNull
	public List<LibraryTransformer> getLibraryTransformers() {
		return transformationCoordinator.getTransformers();
	}

	@Override
	public void addRepository(@NotNull String url) {
		String repo = requireNonNull(url, "url").endsWith("/") ? url : url + '/';
		synchronized (repositories) {
			repositories.add(repo);
		}
	}

	@Override
	public void addMavenCentral() {
		addRepository(Repositories.MAVEN_CENTRAL);
	}

	@Override
	public void addSonatype() {
		addRepository(Repositories.SONATYPE);
	}

	@Override
	public void addJitPack() {
		addRepository(Repositories.JITPACK);
	}

	@Override
	public void addMavenLocal() {
		addRepository(Repositories.mavenLocal());
	}

	@Override
	@NotNull
	public Collection<String> getRepositories() {
		List<String> urls;
		synchronized (repositories) {
			urls = new LinkedList<>(repositories);
		}

		return Collections.unmodifiableList(urls);
	}

	@Override
	public void setRepositoryResolutionMode(@NotNull ResolutionMode mode) {
		this.resolutionMode = requireNonNull(mode, "mode");
	}

	@Override
	@NotNull
	public ResolutionMode getRepositoryResolutionMode() {
		return resolutionMode;
	}

	@Override
	public void setVerbosityMode(@NotNull VerbosityMode mode) {
		this.verbosityMode = requireNonNull(mode, "mode");
	}

	@Override
	@NotNull
	public VerbosityMode getVerbosityMode() {
		return verbosityMode;
	}

	@Override
	public void printLoadedLibrariesSummary() {
		int loaded = loadedLibraries.size();
		int failed = failedLibraries.size();
		int total = loaded + failed;

		if (total == 0) {
			logger.info("No libraries were loaded.");
			return;
		}

		// Single-line summary
		String message = failed == 0
				? "Loaded " + loaded + " libraries successfully"
				: loaded == 0
				? "Failed to load " + failed + " libraries"
				: "Loaded " + loaded + " and failed" + failed + " libraries";

		if (failed > 0)
			logger.warn(message);
		else
			logger.info(message);

		// Show failed libraries for troubleshooting
		if (!failedLibraries.isEmpty() && verbosityMode != VerbosityMode.QUIET) {
			for (Map.Entry<LibraryRequest, Exception> entry : failedLibraries.entrySet()) {
				logger.warn("  Failed: " + entry.getKey() + " - " + entry.getValue().getMessage());
			}
		}
	}

	@NotNull
	public LibraryRequest adaptLibrary(@NotNull Object library) {
		requireNonNull(library, "library");

		LibraryAdapter<Object> adapter = resolveAdapter(library.getClass());
		if (adapter == null) {
			throw new IllegalArgumentException("No LibraryAdapter registered for " + library.getClass().getName());
		}

		return adapter.adapt(library);
	}

	@Nullable
	public String findSkipReason(@NotNull LibraryRequest request) {
		String mavenMetadata = findPresentMavenMetadata(request);
		if (mavenMetadata != null) {
			return "maven metadata present: " + mavenMetadata;
		}

		String classpathJar = findPresentClasspathJar(request);
		if (classpathJar != null) {
			return "classpath jar present: " + classpathJar;
		}

		return null;
	}

	@Nullable
	private String findPresentMavenMetadata(@NotNull LibraryRequest request) {
		if (!request.isSkipIfPresent() || request.hasRelocations()) {
			return null;
		}

		String groupId = LibraryHelper.replaceWithDots(request.getGroupId());
		String artifactId = LibraryHelper.replaceWithDots(request.getArtifactId());
		String resource = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";

		ClassLoader classLoader = getDescriptorClassLoader();
		return isResourcePresent(classLoader, resource) ? resource : null;
	}

	@Nullable
	private String findPresentClasspathJar(@NotNull LibraryRequest request) {
		if (!request.isSkipIfPresent() || request.hasRelocations())
			return null;

		String fileName = buildExpectedJarName(request);
		if (fileName == null)
			return null;

		ClassLoader classLoader = getDescriptorClassLoader();
		String jarPath = findMatchingJarPath(classLoader, fileName);
		if (jarPath != null)
			return jarPath;

		String classPath = System.getProperty("java.class.path");
		if (classPath == null || classPath.isBlank())
			return null;

		for (String entry : classPath.split(File.pathSeparator))
			if (entry.endsWith(fileName))
				return entry;

		return null;
	}

	@Nullable
	private String buildExpectedJarName(@NotNull LibraryRequest request) {
		String artifactId = LibraryHelper.replaceWithDots(request.getArtifactId());
		String version = LibraryHelper.replaceWithDots(request.getVersion());
		if (artifactId.isBlank() || version.isBlank())
			return null;

		StringBuilder name = new StringBuilder(artifactId).append('-').append(version);
		if (request.hasClassifier()) name.append('-').append(request.getClassifier());

		return name.append(".jar").toString();
	}

	@Nullable
	private String findMatchingJarPath(@Nullable ClassLoader classLoader, @NotNull String fileName) {
		if (classLoader == null) return null;

		for (ClassLoader current = classLoader; current != null; current = current.getParent()) {
			if (current instanceof URLClassLoader urlClassLoader) {
				String found = matchJarUrl(urlClassLoader.getURLs(), fileName);
				if (found != null) return found;
			}

			URL[] urls = reflectUrls(current);
			String found = matchJarUrl(urls, fileName);
			if (found != null) return found;
		}

		return null;
	}

	@Nullable
	private String matchJarUrl(@Nullable URL[] urls, @NotNull String fileName) {
		if (urls == null) return null;

		for (URL url : urls) {
			if (url == null)
				continue;

			String path = url.getPath();
			if (path != null && path.endsWith(fileName))
				return path;
		}

		return null;
	}

	@Nullable
	private URL[] reflectUrls(@NotNull ClassLoader classLoader) {
		try {
			Method method = classLoader.getClass().getMethod("getURLs");
			Object result = method.invoke(classLoader);

			return (URL[]) result;
		} catch (ReflectiveOperationException | ClassCastException ignored) {
			return null;
		}
	}

	private boolean isResourcePresent(@Nullable ClassLoader classLoader, @NotNull String resource) {
		String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
		if (classLoader != null) {
			for (ClassLoader current = classLoader; current != null; current = current.getParent()) {
				if (current.getResource(normalized) != null)
					return true;
			}
		}

		return ClassLoader.getSystemResource(normalized) != null;
	}

	public void logSkippedLibrary(@NotNull LibraryRequest request, @NotNull String reason) {
		String message = "Skipping library " + request + " (" + reason + ")";
		switch (verbosityMode) {
			case VERBOSE, NORMAL -> logger.info(message);
			case SUMMARY -> logger.debug(message);
		}
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private LibraryAdapter<Object> resolveAdapter(@NotNull Class<?> type) {
		synchronized (libraryAdapters) {
			LibraryAdapter<?> adapter = libraryAdapters.get(type);
			if (adapter != null) {
				return (LibraryAdapter<Object>) adapter;
			}

			for (Map.Entry<Class<?>, LibraryAdapter<?>> entry : libraryAdapters.entrySet()) {
				if (entry.getKey().isAssignableFrom(type)) {
					return (LibraryAdapter<Object>) entry.getValue();
				}
			}
		}

		return null;
	}

	/**
	 * Gets all the possible download URLs for this library.
	 *
	 * @param library the library to resolve
	 * @return download URLs
	 */
	protected Collection<String> resolveLibrary(@NotNull LibraryRequest library) {
		Set<String> urls = new LinkedHashSet<>(requireNonNull(library, "library").getUrls());
		Collection<String> repos = resolveRepositories(library);

		for (String repository : repos) {
			urls.add(repository + LibraryHelper.getPath(library));
		}

		return Collections.unmodifiableSet(urls);
	}

	/**
	 * Resolves the repository URLs for this library.
	 *
	 * @param library the library to resolve repositories for
	 * @return the resolved repositories
	 */
	public Collection<String> resolveRepositories(@NotNull LibraryRequest library) {
		return switch (getRepositoryResolutionMode()) {
			case GLOBAL_FIRST -> Stream.of(
							getRepositories(),
							library.getRepositories(),
							library.getFallbackRepositories())
					.flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			case LIBRARY_FIRST -> Stream.of(
							library.getRepositories(),
							library.getFallbackRepositories(),
							getRepositories())
					.flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			default -> Stream.of(
							library.getRepositories(),
							getRepositories(),
							library.getFallbackRepositories())
					.flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
		};
	}

	@NotNull
	protected DownloadAttempt downloadLibraryAttempt(@NotNull String url) {
		try {
			URLConnection connection = java.net.URI.create(requireNonNull(url, "url")).toURL().openConnection();

			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			connection.setRequestProperty("User-Agent", USER_AGENT);

			if (connection instanceof HttpURLConnection httpConnection) {
				int responseCode = httpConnection.getResponseCode();
				if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
					return createHttpDownloadFailure(httpConnection, responseCode);
				}
			}

			try (InputStream in = connection.getInputStream()) {
				int len;
				byte[] buf = new byte[8192];
				ByteArrayOutputStream out = new ByteArrayOutputStream();

				try {
					while ((len = in.read(buf)) != -1) {
						out.write(buf, 0, len);
					}
				} catch (SocketTimeoutException e) {
					return DownloadAttempt.failure(Level.WARN, "Download timed out: " + connection.getURL(), false);
				}

				if (verbosityMode == VerbosityMode.VERBOSE)
					logger.info("Downloaded library " + connection.getURL());

				return DownloadAttempt.success(out.toByteArray());
			}
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException(e);
		} catch (FileNotFoundException e) {
			return DownloadAttempt.failure(Level.INFO, "File not found: " + url, true);
		} catch (SocketTimeoutException e) {
			return DownloadAttempt.failure(Level.WARN, "Download timed out: " + url, false);
		} catch (UnknownHostException e) {
			return DownloadAttempt.failure(Level.WARN, "Unknown host: " + url, false);
		} catch (IOException e) {
			return DownloadAttempt.failure(Level.WARN, "Download failed: " + url + " (" + e.getMessage() + ")", false);
		}
	}

	/**
	 * Downloads a library jar and returns the contents as a byte array.
	 *
	 * @param url the URL to the library jar
	 * @return downloaded jar as byte array or null if nothing was downloaded
	 */
	protected byte[] downloadLibraryBytes(@NotNull String url) {
		return downloadLibraryAttempt(url).getBytes();
	}

	@NotNull
	private DownloadAttempt createHttpDownloadFailure(@NotNull HttpURLConnection connection, int responseCode) throws IOException {
		String url = connection.getURL().toString();
		String responseMessage = connection.getResponseMessage();
		String suffix = responseMessage == null || responseMessage.isBlank()
				? ""
				: " " + responseMessage;

		return responseCode == HttpURLConnection.HTTP_NOT_FOUND
				? DownloadAttempt.failure(Level.INFO, "File not found (" + responseCode + suffix + "): " + url, true)
				: DownloadAttempt.failure(Level.WARN, "Download failed (" + responseCode + suffix + "): " + url, false);
	}

	@Override
	@NotNull
	public <T> Path downloadLibrary(@NotNull T library) throws IOException, URISyntaxException, NoSuchAlgorithmException {
		LibraryRequest adaptedLibrary = adaptLibrary(library);
		LibraryRequest normalizedLibrary = LibraryHelper.normalize(adaptedLibrary);
		Path file = artifactDownloadCoordinator.download(normalizedLibrary);

		String relocatedPath = LibraryHelper.getRelocatedPath(normalizedLibrary);
		if (relocatedPath != null)
			file = relocate(file, relocatedPath, normalizedLibrary.getRelocations());

		return transformationCoordinator.transform(normalizedLibrary, file);
	}

	/**
	 * Processes the input jar and generates an output jar with the provided
	 * relocation rules applied, then returns the path to the relocated jar.
	 *
	 * @param in          input jar
	 * @param out         output jar
	 * @param relocations relocations to apply
	 * @return the relocated file
	 */
	@NotNull
	protected Path relocate(@NotNull Path in, @NotNull String out, @NotNull Collection<RelocationRule> relocations) {
		return relocationCoordinator.relocate(in, out, relocations);
	}

	/**
	 * Resolves and loads transitive libraries for a given library. This method ensures that
	 * all libraries on which the provided library depends are properly loaded.
	 *
	 * @param library the primary library for which transitive libraries need to be resolved and loaded.
	 * @throws NullPointerException if the provided library is null.
	 * @see #loadLibrary(Object)
	 */
	protected void resolveTransitiveLibraries(@NotNull LibraryRequest library) {
		requireNonNull(library, "library");

		LibraryRequest normalized = LibraryHelper.normalize(library);
		for (var artifact : dependencyResolver().resolve(normalized, resolveRepositories(normalized))) {
			loadLibrary(dependencyRequest(artifact, normalized));
		}
	}

	/**
	 * Resolves the transitive dependencies of libraries that load together as one dependency graph, so every
	 * artifact loads in one version. A dependency loads once for every distinct way the libraries that need it
	 * load, with their relocations and class loader, as it would for each library on its own.
	 *
	 * @param libraries the libraries to load together
	 * @return the requests that load the libraries and their dependencies, none of them resolving further
	 * @throws IllegalStateException if two libraries that resolve transitive dependencies are the same artifact
	 */
	@NotNull
	protected List<LibraryRequest> resolveDependencyGraph(@NotNull List<LibraryRequest> libraries) {
		List<LibraryRequest> roots = libraries.stream()
				.filter(LibraryRequest::isResolveTransitiveDependencies)
				.map(LibraryHelper::normalize)
				.toList();
		if (roots.isEmpty()) return libraries;

		Map<String, LibraryRequest> rootsByArtifact = new LinkedHashMap<>();
		for (LibraryRequest root : roots) {
			LibraryRequest existing = rootsByArtifact.putIfAbsent(artifactKey(root.getGroupId(), root.getArtifactId(), root.getClassifier()), root);
			if (existing != null)
				throw new IllegalStateException("Libraries " + existing + " and " + root + " are the same artifact in one dependency graph");
		}

		Set<String> repositories = new LinkedHashSet<>();
		roots.forEach(root -> repositories.addAll(resolveRepositories(root)));

		Set<LibraryRequest> requests = new LinkedHashSet<>();
		libraries.stream().filter(library -> !library.isResolveTransitiveDependencies()).forEach(requests::add);
		for (ResolvedDependency dependency : dependencyResolver().resolve(roots, repositories)) {
			ResolvedArtifact artifact = dependency.getArtifact();
			LibraryRequest root = rootsByArtifact.remove(artifactKey(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier()));
			if (root != null) requests.add(root.toBuilder().resolveTransitiveDependencies(false).build());

			for (LibraryRequest dependent : dependency.getDependents()) {
				requests.add(root != null
						? root.toBuilder()
								.resolveTransitiveDependencies(false)
								.isolated(dependent.isIsolated())
								.loader(dependent.getLoader())
								.clearRelocations()
								.relocations(dependent.getRelocations())
								.build()
						: dependencyRequest(artifact, dependent));
			}
		}

		// Every library loads, also one the resolver left out of the graph.
		rootsByArtifact.values().forEach(root -> requests.add(root.toBuilder().resolveTransitiveDependencies(false).build()));
		return List.copyOf(requests);
	}

	@NotNull
	private synchronized DependencyResolver dependencyResolver() {
		if (dependencyResolver == null)
			dependencyResolver = resolverFactory.create(this);

		return dependencyResolver;
	}

	/**
	 * Builds the request that loads a resolved dependency the way the library that needs it loads.
	 */
	@NotNull
	private static LibraryRequest dependencyRequest(@NotNull ResolvedArtifact artifact, @NotNull LibraryRequest dependent) {
		return LibraryRequest.builder()
				.groupId(artifact.getGroupId())
				.artifactId(artifact.getArtifactId())
				.version(artifact.getBaseVersion())
				.classifier(artifact.getClassifier())
				.url(artifact.getFile().toUri().toString())
				.isolated(dependent.isIsolated())
				.loader(dependent.getLoader())
				.relocations(dependent.getRelocations())
				.build();
	}

	@NotNull
	private static String artifactKey(@NotNull String groupId, @NotNull String artifactId, @Nullable String classifier) {
		return groupId + ':' + artifactId + ':' + (classifier == null ? "" : classifier);
	}

	@Override
	public <T> void loadLibrary(@NotNull T library, @NotNull Path file) {
		LibraryRequest request = adaptLibrary(library);
		requireNonNull(file, "file");

		String skipReason = findSkipReason(request);
		if (skipReason != null) {
			logSkippedLibrary(request, skipReason);
			return;
		}

		if (request.isIsolated()) {
			addToIsolatedClasspath(request, file);
		} else {
			addToClasspath(file);
		}
	}

	@Override
	public <T> void loadLibrary(@NotNull T library) {
		LibraryRequest request = adaptLibrary(library);

		String skipReason = findSkipReason(request);
		if (skipReason != null) {
			logSkippedLibrary(request, skipReason);
			return;
		}

		// Log based on verbosity mode
		switch (verbosityMode) {
			case VERBOSE, NORMAL -> logger.info("Loading library " + request);
			case SUMMARY -> logger.debug("Loading library " + request);
			case QUIET -> { /* No logging */ }
		}

		try {
			Path file = downloadLibrary(request);
			loadDownloadedLibrary(request, file);
		} catch (IOException | URISyntaxException | NoSuchAlgorithmException e) {
			recordLoadFailure(request, e);
			throw new RuntimeException("Failed to load library " + request, e);
		} catch (RuntimeException e) {
			recordLoadFailure(request, e);
			throw e;
		}
	}

    @Override
	@SafeVarargs
	public final <T> void loadLibraries(@NotNull T... libraries) {
		loadLibraries(Arrays.asList(libraries));
	}

	@Override
	public <T> void loadLibraries(@NotNull Collection<? extends T> libraries) {
		libraryBatchLoader.loadLibraries(libraries);
	}

	@NotNull
	public LibraryLoadMode getLibraryLoadMode() {
		return libraryLoadMode;
	}

	public void logLibraryLoadStart(@NotNull LibraryRequest request) {
		switch (verbosityMode) {
			case VERBOSE, NORMAL -> logger.info("Loading library " + request);
			case SUMMARY -> logger.debug("Loading library " + request);
			case QUIET -> { /* No logging */ }
		}
	}

	public void loadDownloadedLibrary(@NotNull LibraryRequest request, @NotNull Path file) {
		if (request.isResolveTransitiveDependencies())
			resolveTransitiveLibraries(request);

		loadLibrary(request, file);
		loadedLibraries.add(request);

		if (verbosityMode == VerbosityMode.VERBOSE)
			logger.info("Successfully loaded " + request);
	}

	public void recordLoadFailure(@NotNull LibraryRequest request, @NotNull Exception exception) {
		failedLibraries.put(request, exception);
	}

	@NotNull
	public <T> T awaitFuture(
			@NotNull CompletableFuture<T> future,
			@NotNull String interruptedMessage
	) throws IOException, URISyntaxException, NoSuchAlgorithmException {
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(interruptedMessage, e);
		} catch (ExecutionException e) {
			throw rethrowAsyncCause(e.getCause());
		}
	}

	@NotNull
	private LibraryBatchLoader createLibraryBatchLoader(@NotNull LibraryLoadMode loadMode) {
		return switch (loadMode) {
			case PARALLEL -> new ParallelLibraryLoader(this);
			case SEQUENTIAL -> new SequentialLibraryLoader(this);
		};
	}

	@NotNull
	private LibraryLoadMode resolveLibraryLoadMode() {
		String configuredValue = System.getProperty(LibraryLoadMode.SYSTEM_PROPERTY);
		if (configuredValue == null || configuredValue.isBlank())
			configuredValue = System.getenv(LibraryLoadMode.ENV_VARIABLE);

		try {
			return LibraryLoadMode.resolve(configuredValue);
		} catch (IllegalArgumentException e) {
			logger.warn("Invalid load mode '" + configuredValue + "', defaulting to PARALLEL");
			return LibraryLoadMode.PARALLEL;
		}
	}

	@NotNull
	private RuntimeException rethrowAsyncCause(Throwable cause) throws IOException, URISyntaxException, NoSuchAlgorithmException {
		Throwable unwrapped = unwrapAsyncCause(cause);
		if (unwrapped instanceof IOException ioException)
			throw ioException;
		if (unwrapped instanceof URISyntaxException uriSyntaxException)
			throw uriSyntaxException;
		if (unwrapped instanceof NoSuchAlgorithmException noSuchAlgorithmException)
			throw noSuchAlgorithmException;
		if (unwrapped instanceof RuntimeException runtimeException)
			throw runtimeException;

		throw new RuntimeException("Unexpected asynchronous failure", unwrapped);
	}

	@NotNull
	private Throwable unwrapAsyncCause(Throwable cause) {
		Throwable current = cause;
		while (current instanceof CompletionException completionException && completionException.getCause() != null)
			current = completionException.getCause();

		return current;
	}

	/**
	 * Gets the logger for this library manager.
	 *
	 * @return the logger
	 */
	@NotNull
	public Logger getLogger() {
		return logger;
	}

	@NotNull
	protected ClassLoader getDescriptorClassLoader() {
		return getClass().getClassLoader();
	}

	@Override
	public final synchronized void setDescriptorSources(@NotNull Collection<URL> sources) {
		List<URL> copy = List.copyOf(requireNonNull(sources, "sources"));
		if (copy.isEmpty()) throw new IllegalArgumentException("At least one descriptor source is needed");
		if (descriptorCatalog != null)
			throw new IllegalStateException("Descriptors have already been read; set their sources before loading them");

		descriptorSources = copy;
	}

	@Override
	public final synchronized void loadDescriptors() {
		if (defaultDescriptorLibrariesLoaded) return;

		logDescriptorProgress("Loading declared libraries");
		DescriptorCatalog catalog = descriptorCatalog();
		if (catalog.isEmpty()) {
			logDescriptorProgress("No declared libraries found");
			defaultDescriptorLibrariesLoaded = true;
			return;
		}

		loadDescriptorLibraries(catalog, catalog.getDefaultLibraries());
		defaultDescriptorLibrariesLoaded = true;
	}

	@Override
	public final synchronized void loadGroups(@NotNull String... groups) {
		Set<String> requested = new LinkedHashSet<>(List.of(requireNonNull(groups, "groups")));
		if (requested.isEmpty()) return;

		DescriptorCatalog catalog = descriptorCatalog();
		Set<String> unknown = new LinkedHashSet<>(requested);
		unknown.removeAll(catalog.getGroups());
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("No Attache descriptor declares the group(s) " + String.join(", ", unknown)
					+ "; declared groups: " + (catalog.getGroups().isEmpty() ? "none" : String.join(", ", catalog.getGroups())));
		}

		logDescriptorProgress("Loading declared libraries of group(s) " + String.join(", ", requested));
		loadDescriptorLibraries(catalog, catalog.getGroupLibraries(requested));
	}

	@NotNull
	private DescriptorCatalog descriptorCatalog() {
		if (descriptorCatalog == null) {
			descriptorCatalog = DescriptorCatalog.merge(descriptorSources == null
					? classpathDescriptorLoader.load(getDescriptorClassLoader(), getClass())
					: classpathDescriptorLoader.load(descriptorSources));
			if (descriptorCatalog.isAddMavenCentral()) addMavenCentral();
			descriptorCatalog.getRepositories().forEach(this::addRepository);
		}

		return descriptorCatalog;
	}

	private void loadDescriptorLibraries(@NotNull DescriptorCatalog catalog, @NotNull List<AttacheDescriptorLibrary> libraries) {
		List<LibraryRequest> requests = libraries.stream()
				.filter(library -> loadedDescriptorLibraries.add(library.coordinatesKey()))
				.map(catalog::toLibraryRequest)
				.toList();
		if (!requests.isEmpty()) loadLibraries(resolveDependencyGraph(requests));

		if (verbosityMode == VerbosityMode.VERBOSE) {
			logger.info("Finished loading " + requests.size() + " libraries");
		}
	}

	private void logDescriptorProgress(@NotNull String message) {
		if (verbosityMode == VerbosityMode.VERBOSE || verbosityMode == VerbosityMode.SUMMARY) {
			logger.info(message);
		}
	}

	/**
	 * Gets the global isolated class loader.
	 *
	 * @return the isolated class loader
	 */
	@NotNull
	public IsolatedClassLoader getGlobalIsolatedClassLoader() {
		return globalIsolatedClassLoader;
	}

	/**
	 * Gets an isolated class loader by ID.
	 *
	 * @param loaderId the loader ID
	 * @return the isolated class loader or null if not found
	 */
	@Nullable
	public IsolatedClassLoader getIsolatedClassLoaderById(@NotNull String loaderId) {
		return isolatedLibraries.get(loaderId);
	}

	/**
	 * Closes all isolated classloaders managed by this library manager.
	 * <p>
	 * This should be called when the library manager is no longer needed,
	 * or use try-with-resources for automatic cleanup.
	 * <p>
	 * This fixes resource leaks and Windows file locking issues.
	 */
	@Override
	public void close() {
		relocationCoordinator.close();

		if (dependencyResolver != null) {
			try {
				dependencyResolver.close();
			} catch (Exception e) {
				logger.error("Failed to close dependency resolver", e);
			}
		}

		// Close global isolated classloader
		try {
			globalIsolatedClassLoader.close();
		} catch (Exception e) {
			logger.error("Failed to close global isolated classloader", e);
		}

		// Close all named isolated classloaders
		for (Map.Entry<String, IsolatedClassLoader> entry : isolatedLibraries.entrySet()) {
			try {
				entry.getValue().close();
			} catch (Exception e) {
				logger.error("Failed to close isolated classloader '" + entry.getKey() + "'", e);
			}
		}

		isolatedLibraries.clear();
	}

	public static final class DownloadAttempt {
		private final byte[] bytes;
		private final boolean notFound;

		private DownloadAttempt(byte[] bytes, boolean notFound) {
			this.bytes = bytes;
			this.notFound = notFound;
		}

		@NotNull
		public static DownloadAttempt success(byte @NotNull [] bytes) {
			return new DownloadAttempt(requireNonNull(bytes, "bytes"), false);
		}

		@NotNull
		public static DownloadAttempt failure(@NotNull Level level, @NotNull String message, boolean notFound) {
			requireNonNull(level, "level");
			requireNonNull(message, "message");
			return new DownloadAttempt(null, notFound);
		}

		byte @Nullable [] getBytes() {
			return bytes;
		}

		boolean isSuccess() {
			return bytes != null;
		}

		boolean isNotFound() {
			return notFound;
		}
	}
}
