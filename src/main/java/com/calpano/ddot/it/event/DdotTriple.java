package com.calpano.ddot.it.event;

import org.jspecify.annotations.Nullable;

/** A ddot triple as indexed: subject, optional predicate (null = untyped link), object. */
public record DdotTriple(String from, @Nullable String type, String to) {}
