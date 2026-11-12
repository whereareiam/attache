package me.whereareiam.attache.common;

import me.whereareiam.attache.LibraryTransformer;
import me.whereareiam.attache.common.util.ArtifactIntegrity;
import me.whereareiam.attache.model.LibraryRequest;
import me.whereareiam.attache.type.VerbosityMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Runs the registered {@link LibraryTransformer}s on downloaded jars and keeps their results in the save directory.
 * <p>
 * A transformed jar lies beside the jar it was made from, named after it with the transformer's identifier and
 * version appended. A marker beside it holds the fingerprint of that source, so a result is reused only while
 * its source is unchanged; it also records when the transformer left the jar unchanged.
 */
final class TransformationCoordinator {
	private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]");
	private static final String UNCHANGED = " unchanged";

	private final BaseLibraryManager libraryManager;
	private final Path saveDirectory;
	private final List<LibraryTransformer> transformers = new ArrayList<>();
	private final ConcurrentMap<Path, CompletableFuture<Path>> inFlightTransformations = new ConcurrentHashMap<>();

	TransformationCoordinator(@NotNull BaseLibraryManager libraryManager, @NotNull Path saveDirectory) {
		this.libraryManager = requireNonNull(libraryManager, "libraryManager");
		this.saveDirectory = requireNonNull(saveDirectory, "saveDirectory").toAbsolutePath().normalize();
	}

	void register(@NotNull LibraryTransformer transformer) {
		requireNonNull(transformer, "transformer");
		String id = requireNonNull(transformer.getId(), "transformer id");
		requireNonNull(transformer.getVersion(), "transformer version");
		if (id.isBlank()) throw new IllegalArgumentException("A library transformer needs an identifier");

		synchronized (transformers) {
			for (int i = 0; i < transformers.size(); i++) {
				if (transformers.get(i).getId().equals(id)) {
					transformers.set(i, transformer);
					return;
				}
			}

			transformers.add(transformer);
		}
	}

	void unregister(@NotNull String id) {
		requireNonNull(id, "id");
		synchronized (transformers) {
			transformers.removeIf(transformer -> transformer.getId().equals(id));
		}
	}

	@NotNull
	List<LibraryTransformer> getTransformers() {
		synchronized (transformers) {
			return List.copyOf(transformers);
		}
	}

	boolean isRegistered(@NotNull String id) {
		requireNonNull(id, "id");
		synchronized (transformers) {
			return transformers.stream().anyMatch(transformer -> transformer.getId().equals(id));
		}
	}

	/**
	 * Runs every registered transformer that applies to the library on its jar, in registration order.
	 *
	 * @param library the normalized library
	 * @param file    the downloaded and relocated jar
	 * @return the jar to load
	 */
	@NotNull
	Path transform(@NotNull LibraryRequest library, @NotNull Path file) {
		requireNonNull(library, "library");
		requireNonNull(file, "file");

		List<LibraryTransformer> registered;
		synchronized (transformers) {
			if (transformers.isEmpty()) return file;
			registered = List.copyOf(transformers);
		}

		Path current = file;
		String name = cacheName(file);
		for (LibraryTransformer transformer : registered) {
			if (!transformer.appliesTo(library)) continue;

			name = name + '-' + safe(transformer.getId()) + '-' + safe(transformer.getVersion());
			current = transformOnce(transformer, library, current, saveDirectory.resolve(name + ".jar"));
		}

		return current;
	}

	/**
	 * Gets the name, relative to the save directory and without {@code .jar}, that the results made from the
	 * jar start with; a jar outside the save directory, such as a local file, keeps its results at the top.
	 */
	@NotNull
	private String cacheName(@NotNull Path file) {
		Path absolute = file.toAbsolutePath().normalize();
		String name = absolute.startsWith(saveDirectory)
				? saveDirectory.relativize(absolute).toString().replace('\\', '/')
				: absolute.getFileName().toString();
		return name.endsWith(".jar") ? name.substring(0, name.length() - ".jar".length()) : name;
	}

	@NotNull
	private Path transformOnce(
			@NotNull LibraryTransformer transformer,
			@NotNull LibraryRequest library,
			@NotNull Path in,
			@NotNull Path file
	) {
		CompletableFuture<Path> future = new CompletableFuture<>();
		CompletableFuture<Path> existing = inFlightTransformations.putIfAbsent(file, future);
		if (existing != null)
			return awaitTransformation(existing);

		try {
			Path transformed = transformNow(transformer, library, in, file);
			future.complete(transformed);
			return transformed;
		} catch (RuntimeException e) {
			future.completeExceptionally(e);
			throw e;
		} finally {
			inFlightTransformations.remove(file, future);
		}
	}

	@NotNull
	private Path transformNow(
			@NotNull LibraryTransformer transformer,
			@NotNull LibraryRequest library,
			@NotNull Path in,
			@NotNull Path file
	) {
		Path tmpOut = null;
		try {
			String fingerprint = HexFormat.of().formatHex(ArtifactIntegrity.sha256(in));
			Path marker = file.resolveSibling(file.getFileName() + ".source.sha256");
			if (Files.isRegularFile(marker)) {
				String recorded = Files.readString(marker);
				if (recorded.equals(fingerprint + UNCHANGED))
					return in;
				if (recorded.equals(fingerprint) && Files.exists(file))
					return file;
			}

			Files.createDirectories(file.getParent());
			Files.deleteIfExists(marker);
			tmpOut = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");

			Path result = transformer.transform(library, in, tmpOut);
			if (result.equals(in)) {
				Files.deleteIfExists(file);
				Files.writeString(marker, fingerprint + UNCHANGED);
				return in;
			}
			if (!result.equals(tmpOut))
				throw new IllegalStateException("Library transformer '" + transformer.getId()
						+ "' returned " + result + " instead of its input or output");

			Files.move(tmpOut, file, StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(marker, fingerprint);

			if (libraryManager.getVerbosityMode() == VerbosityMode.VERBOSE)
				libraryManager.getLogger().info("Transformer " + transformer.getId() + " applied to " + in.getFileName());

			return file;
		} catch (IOException e) {
			throw new UncheckedIOException("Library transformer '" + transformer.getId() + "' failed on " + library, e);
		} finally {
			deleteTemporaryOutput(tmpOut);
		}
	}

	@NotNull
	private static String safe(@NotNull String value) {
		return UNSAFE_NAME.matcher(value).replaceAll("_");
	}

	private void deleteTemporaryOutput(@Nullable Path file) {
		if (file == null)
			return;

		try {
			Files.deleteIfExists(file);
		} catch (IOException failure) {
			libraryManager.getLogger().debug("Could not remove temporary transformation output " + file);
		}
	}

	@NotNull
	private Path awaitTransformation(@NotNull CompletableFuture<Path> future) {
		try {
			return future.join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException)
				throw runtimeException;

			throw e;
		}
	}
}
