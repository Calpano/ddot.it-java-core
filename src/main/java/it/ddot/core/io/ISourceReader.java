package it.ddot.core.io;


import it.ddot.core.source.ISourceFormat;
import it.ddot.core.source.ISourceResourceAddress;
import it.ddot.core.source.ISourceResourceData;
import it.ddot.core.source.ISourceScheme;

import java.io.IOException;

/**
 * 1 reader handles 1 kind of source (e.g. 'markdown'). A source is, e.g., a single Markdown file, iCal feed, Todoist
 * account. Each source has n items, e.g., Markdown sections.
 */
public interface ISourceReader {

    ISourceScheme getScheme();

    /** read data of a resource */
    void readTo(ISourceResourceAddress sourceResourceAddress, ISourceHandler sourceHandler) throws IOException;

    /** read meta-data about a resource */
    ISourceResourceData resourceData(ISourceResourceAddress sourceResourceAddress) throws IOException;

    /** the kind of source this reader can process */
    ISourceFormat sourceKind();

}
