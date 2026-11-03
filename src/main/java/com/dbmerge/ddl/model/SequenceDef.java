package com.dbmerge.ddl.model;

public record SequenceDef(
        String name,
        String minValue,
        String maxValue,
        String incrementBy,
        boolean cycle,
        boolean ordered,
        long cacheSize) {

    /** LAST_NUMBER is deliberately excluded: it always differs between live databases. */
    public String params() {
        return "INCREMENT " + incrementBy + " MIN " + minValue + " MAX " + maxValue
                + " CACHE " + cacheSize + (cycle ? " CYCLE" : "") + (ordered ? " ORDER" : "");
    }
}
