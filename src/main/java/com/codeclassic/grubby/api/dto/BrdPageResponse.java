package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Paginated wrapper returned by GET /api/v1/brd
 */
@Data
@AllArgsConstructor
public class BrdPageResponse {
    private List<BrdListItemResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;
}
