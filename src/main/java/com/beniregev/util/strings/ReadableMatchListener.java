package com.beniregev.util.strings;

public interface ReadableMatchListener<T> {
    // return true to continue matching
    boolean match(T value);
}
