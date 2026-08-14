package it.ddot.core.source;


import it.ddot.core.io.ISourceReader;

import java.io.IOException;
import java.util.stream.Stream;

/**
 * E.g., a Todoist account, a directory full of Markdown files.
 * <p>
 * The technical source for resolving via I/O the {@link ISourceResourceAddress} of a single, configured source. This
 * resembles the 'scheme handler' part of URI infrastructure. See also {@link ISourceReader} for getting and processing
 * the content of single source resource. That is the 'mimeType' part of URI infrastructure.
 */
public interface ISource {

    /** list all resources of a source */
    Stream<ISourceResourceAddress> resourceAddresses() throws IOException;

    /** the kind of source this reader can process */
    ISourceScheme sourceScheme();

}
