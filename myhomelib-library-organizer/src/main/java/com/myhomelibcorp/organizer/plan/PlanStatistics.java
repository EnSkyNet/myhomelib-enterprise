package com.myhomelibcorp.organizer.plan;

import java.util.List;

public record PlanStatistics(int total, int noOp, int renameOrMove, int conversions, int exactDuplicates,
                             int reviewDuplicates, int conflicts, int unsupported, int errors) {
    public static PlanStatistics from(List<OrganizationPlanItem> items) {
        int noOp = 0;
        int moves = 0;
        int conversions = 0;
        int exactDuplicates = 0;
        int reviewDuplicates = 0;
        int conflicts = 0;
        int unsupported = 0;
        int errors = 0;
        List<OrganizationPlanItem> safeItems = items == null ? List.of() : items;
        for (OrganizationPlanItem item : safeItems) {
            if (item == null) continue;
            switch (item.action()) {
                case NOOP, KEEP_AS_IS -> noOp++;
                case RENAME, MOVE, MOVE_AND_RENAME, EXTRACT -> moves++;
                case OPTIMIZE, OPTIMIZE_AND_MOVE -> conversions++;
                case CONVERT, CONVERT_AND_MOVE -> conversions++;
                case DELETE_EXACT_DUPLICATE -> exactDuplicates++;
                case REVIEW_DUPLICATE -> reviewDuplicates++;
                case CONFLICT -> conflicts++;
                case SKIP_UNSUPPORTED -> unsupported++;
                case ERROR -> errors++;
                case ARCHIVE_ORIGINAL -> moves++;
            }
        }
        return new PlanStatistics(safeItems.size(), noOp, moves, conversions, exactDuplicates,
                reviewDuplicates, conflicts, unsupported, errors);
    }
}
