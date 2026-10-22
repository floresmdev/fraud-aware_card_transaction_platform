package dev.floresm.transactions.api.dto;

import java.util.List;

/** @param nextCursor pass as {@code ?cursor=} to get the next page; {@code null} on the last page */
public record TransactionPageResponse(List<TransactionResponse> items, String nextCursor) {
}
