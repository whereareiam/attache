package me.whereareiam.attache.common;

import me.whereareiam.attache.LibraryTransformer;
import me.whereareiam.attache.LoggingHelper;
import me.whereareiam.attache.model.LibraryRequest;
import me.whereareiam.attache.type.Level;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LibraryTransformerTest {
	@TempDir Path temporary;

	@Test
	void loadsTheTransformedJarAndReusesItAfterARestart() throws Exception {
		Path source = jar("source.jar", "original");
		Appending transformer = new Appending("upper", "1", "+upper");
		Path transformed;
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(transformer);
			transformed = manager.downloadLibrary(library(source));
			assertEquals("original+upper", content(transformed));
			assertTrue(transformed.getFileName().toString().endsWith("-upper-1.jar"), transformed.toString());
			assertTrue(transformed.startsWith(manager.getSaveDirectory()));
		}

		try (Manager restarted = manager()) {
			restarted.registerLibraryTransformer(transformer);
			assertEquals(transformed, restarted.downloadLibrary(library(source)));
		}
		assertEquals(1, transformer.runs.get(), "A restart reuses the transformed jar");
	}

	@Test
	void transformsAgainForAnotherVersionOrAChangedSource() throws Exception {
		Path source = jar("source.jar", "original");
		Appending first = new Appending("mark", "1", "+one");
		Appending second = new Appending("mark", "2", "+two");
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(first);
			assertEquals("original+one", content(manager.downloadLibrary(library(source))));
			manager.registerLibraryTransformer(second);
			assertEquals("original+two", content(manager.downloadLibrary(library(source))));
		}

		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(second);
			manager.downloadLibrary(library(source));
			write(manager.getSaveDirectory().resolve("test/library/1/library-1.jar"), "changed");
			assertEquals("changed+two", content(manager.downloadLibrary(library(source))));
		}
		assertEquals(2, second.runs.get());
	}

	@Test
	void runsTransformersInRegistrationOrder() throws Exception {
		Path source = jar("source.jar", "original");
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(new Appending("first", "1", "+first"));
			manager.registerLibraryTransformer(new Appending("second", "1", "+second"));
			Path transformed = manager.downloadLibrary(library(source));
			assertEquals("original+first+second", content(transformed));
			assertTrue(transformed.getFileName().toString().endsWith("-first-1-second-1.jar"), transformed.toString());
		}
	}

	@Test
	void remembersAJarThatNeedsNoChange() throws Exception {
		Path source = jar("source.jar", "original");
		AtomicInteger runs = new AtomicInteger();
		LibraryTransformer unchanged = new LibraryTransformer() {
			@Override public @NotNull String getId() { return "noop"; }
			@Override public @NotNull String getVersion() { return "1"; }
			@Override public @NotNull Path transform(@NotNull LibraryRequest library, @NotNull Path input, @NotNull Path output) {
				runs.incrementAndGet();
				return input;
			}
		};
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(unchanged);
			Path downloaded = manager.downloadLibrary(library(source));
			assertEquals("original", content(downloaded));
			assertEquals(downloaded, manager.downloadLibrary(library(source)));
		}
		assertEquals(1, runs.get());
	}

	@Test
	void skipsLibrariesATransformerDoesNotApplyTo() throws Exception {
		Path source = jar("source.jar", "original");
		Appending transformer = new Appending("only-other", "1", "+changed", library -> library.getArtifactId().equals("other"));
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(transformer);
			assertEquals("original", content(manager.downloadLibrary(library(source))));
		}
		assertEquals(0, transformer.runs.get());
	}

	@Test
	void replacesAndRemovesTransformersByIdentifier() throws Exception {
		Path source = jar("source.jar", "original");
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(new Appending("a", "1", "+a"));
			manager.registerLibraryTransformer(new Appending("b", "1", "+b"));
			manager.registerLibraryTransformer(new Appending("a", "2", "+A"));
			assertEquals("original+A+b", content(manager.downloadLibrary(library(source))));

			assertEquals(List.of("a", "b"), manager.getLibraryTransformers().stream().map(LibraryTransformer::getId).toList());
			assertEquals("2", manager.getLibraryTransformers().get(0).getVersion());
			assertThrows(UnsupportedOperationException.class, () -> manager.getLibraryTransformers().clear());

			manager.unregisterLibraryTransformer("a");
			assertFalse(manager.hasLibraryTransformer("a"));
			assertTrue(manager.hasLibraryTransformer("b"));
			assertEquals("original+b", content(manager.downloadLibrary(library(source))));
		}
	}

	@Test
	void loadsTheTransformedJarOfAnIsolatedLibrary() throws Exception {
		Path source = jar("source.jar", "original");
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(new Appending("mark", "1", "+mark"));
			manager.loadLibrary(library(source).toBuilder().isolated(true).loader("isolated").build());
			var loader = manager.getIsolatedClassLoaderById("isolated");
			assertNotNull(loader);
			assertEquals(1, loader.getURLs().length);
			assertEquals("original+mark", content(Path.of(loader.getURLs()[0].toURI())));
		}
	}

	@Test
	void reportsTheFailingTransformer() throws Exception {
		Path source = jar("source.jar", "original");
		try (Manager manager = manager()) {
			manager.registerLibraryTransformer(new LibraryTransformer() {
				@Override public @NotNull String getId() { return "broken"; }
				@Override public @NotNull String getVersion() { return "1"; }
				@Override public @NotNull Path transform(@NotNull LibraryRequest library, @NotNull Path input, @NotNull Path output) throws java.io.IOException {
					throw new java.io.IOException("cannot read");
				}
			});
			RuntimeException failure = assertThrows(RuntimeException.class, () -> manager.loadLibrary(library(source)));
			assertTrue(failure.getMessage().contains("broken"), failure.getMessage());
			try (var files = Files.walk(manager.getSaveDirectory())) {
				assertTrue(files.noneMatch(file -> file.getFileName().toString().contains("broken")),
						"A failed transformation leaves nothing behind");
			}
		}
	}

	private Manager manager() {
		return new Manager(temporary);
	}

	private static LibraryRequest library(Path source) {
		return LibraryRequest.builder().groupId("test").artifactId("library").version("1")
				.url(source.toUri().toString()).build();
	}

	private Path jar(String name, String text) throws Exception {
		Path file = temporary.resolve(name);
		write(file, text);
		return file;
	}

	private static void write(Path file, String text) throws Exception {
		var bytes = new ByteArrayOutputStream();
		try (var jar = new JarOutputStream(bytes)) {
			jar.putNextEntry(new JarEntry("value.txt"));
			jar.write(text.getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
		Files.write(file, bytes.toByteArray());
	}

	private static String content(Path path) throws Exception {
		try (var jar = new JarFile(path.toFile()); var input = jar.getInputStream(jar.getJarEntry("value.txt"))) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static final class Appending implements LibraryTransformer {
		private final String id;
		private final String version;
		private final String suffix;
		private final Predicate<LibraryRequest> filter;
		private final AtomicInteger runs = new AtomicInteger();

		Appending(String id, String version, String suffix) {
			this(id, version, suffix, library -> true);
		}

		Appending(String id, String version, String suffix, Predicate<LibraryRequest> filter) {
			this.id = id;
			this.version = version;
			this.suffix = suffix;
			this.filter = filter;
		}

		@Override public @NotNull String getId() { return id; }
		@Override public @NotNull String getVersion() { return version; }
		@Override public boolean appliesTo(@NotNull LibraryRequest library) { return filter.test(library); }

		@Override
		public @NotNull Path transform(@NotNull LibraryRequest library, @NotNull Path input, @NotNull Path output) throws java.io.IOException {
			runs.incrementAndGet();
			try {
				write(output, content(input) + suffix);
			} catch (java.io.IOException e) {
				throw e;
			} catch (Exception e) {
				throw new java.io.IOException(e);
			}
			return output;
		}
	}

	private static final class Manager extends BaseLibraryManager {
		Manager(Path directory) {
			super(new LoggingHelper() {
				@Override public void log(@NotNull Level level, @NotNull String message) { }
				@Override public void log(@NotNull Level level, @NotNull String message, @NotNull Throwable throwable) { }
			}, directory, "libraries");
		}

		@Override protected void addToClasspath(@NotNull Path path) { }
	}
}
