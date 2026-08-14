package it.ddot.core.hostreader;

import it.ddot.core.source.ISourceFormat;
import it.ddot.core.io.ISourceReader;

public abstract class AbstractSourceReader implements ISourceReader {

    final ISourceFormat sourceKind;

    protected AbstractSourceReader(ISourceFormat sourceKind) {this.sourceKind = sourceKind;}

    @Override
    public ISourceFormat sourceKind() {
        return sourceKind;
    }

}
