package com.beniregev.util.strings.threshold;

public interface Thresholder {
    boolean isOverThreshold(int nodeSize, int nodeLevel, int keyIntervalSize);
}
