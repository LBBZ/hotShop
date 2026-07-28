package com.real.common.api;

import java.util.List;

public record CursorSlice<T>(List<T> items, String nextCursor, boolean hasMore) {
    public CursorSlice {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
