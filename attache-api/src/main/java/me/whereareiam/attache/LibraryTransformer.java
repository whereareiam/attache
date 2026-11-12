package me.whereareiam.attache;

import me.whereareiam.attache.model.LibraryRequest;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Changes a library jar after Attache has downloaded and relocated it and before it is loaded.
 * <p>
 * A transformer only changes bytes; where the result is kept is up to the library manager. The manager
 * keeps the transformed jar in its save directory under a name that carries {@link #getId()} and
 * {@link #getVersion()}, and reuses it as long as the jar it was made from is unchanged. A restart
 * therefore does not transform a library again, and a new transformer version transforms it anew:
 * change the version whenever the transformer writes different bytes for the same input.
 * <p>
 * Registered transformers run in the order they were registered, each on the result of the previous one,
 * for every library a manager downloads, including the libraries Attache loads for itself, such as its
 * relocator and resolver. A transformer that loads libraries through the same manager while transforming
 * should leave those libraries out in {@link #appliesTo(LibraryRequest)}.
 * <p>
 * Transformers may run concurrently for different libraries.
 */
public interface LibraryTransformer {
	/**
	 * Gets the identifier of this transformer, unique among the transformers of one library manager.
	 * <p>
	 * The identifier names the transformed jars in the save directory, so it should stay the same
	 * across releases of the transformer.
	 *
	 * @return the identifier
	 */
	@NotNull
	String getId();

	/**
	 * Gets the version of this transformer's output.
	 * <p>
	 * Jars transformed by another version are transformed again.
	 *
	 * @return the version
	 */
	@NotNull
	String getVersion();

	/**
	 * Checks whether this transformer changes the given library at all.
	 * <p>
	 * A library it does not apply to is passed on unchanged without reading its jar.
	 *
	 * @param library the library about to be loaded
	 * @return true if {@link #transform(LibraryRequest, Path, Path)} should run for the library
	 */
	default boolean appliesTo(@NotNull LibraryRequest library) {
		return true;
	}

	/**
	 * Transforms a library jar.
	 * <p>
	 * The transformer reads {@code input} and writes the transformed jar to {@code output}, an empty
	 * temporary file that it may replace and that the manager moves into place once this method returns. When the jar needs no change, it
	 * returns {@code input} and leaves {@code output} alone; the manager then loads the input and
	 * remembers that this input needs no change.
	 *
	 * @param library the library the jar belongs to
	 * @param input   the jar as downloaded, relocated and transformed by the previous transformers
	 * @param output  the file to write the transformed jar to
	 * @return {@code output} if the transformer wrote it, or {@code input} if the jar needs no change
	 * @throws IOException if the jar cannot be read or written
	 */
	@NotNull
	Path transform(@NotNull LibraryRequest library, @NotNull Path input, @NotNull Path output) throws IOException;
}
