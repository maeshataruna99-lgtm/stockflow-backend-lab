package dev.stockflow.warehouse.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Shared pagination policy (common conventions in
 * initial-api-outline.md): page is 0-based, size defaults to 20 and is
 * clamped into 1..100, so no list response can exceed the documented
 * maximum page size of 100. Same policy as catalog-service.
 */
final class Pagination {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private Pagination() {
    }

    static Pageable of(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(safePage, safeSize);
    }
}
