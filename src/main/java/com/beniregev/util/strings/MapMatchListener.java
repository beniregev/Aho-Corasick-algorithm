package com.beniregev.util.strings;

/**
 *
 * @author binyamin.regev
 * @since jdk-1.8.0_162
 * @param <T>
 */
public interface MapMatchListener<T> {
    // return true to continue matching
    boolean match(String haystack, final int startPosition, final int endPosition, final T value);
}
