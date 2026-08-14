package it.ddot.core.kbase.inmem;

import it.ddot.core.kbase.inmem.Kitem;
import it.ddot.core.kbase.IKnowledgeNode;

public class Knode extends Kitem implements IKnowledgeNode {

    public final String id;

    public Knode(String id) { this.id = id; }

    public String id() { return id; }
}
