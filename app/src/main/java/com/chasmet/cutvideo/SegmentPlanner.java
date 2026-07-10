package com.chasmet.cutvideo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Calcule des intervalles vidéo sans dépendre d'Android afin de pouvoir les tester facilement. */
public final class SegmentPlanner {

    private SegmentPlanner() {
    }

    public static List<Segment> split(long totalDurationMs, long segmentDurationMs) {
        if (totalDurationMs <= 0) {
            throw new IllegalArgumentException("La durée totale doit être positive.");
        }
        if (segmentDurationMs <= 0) {
            throw new IllegalArgumentException("La durée d'un morceau doit être positive.");
        }

        List<Segment> segments = new ArrayList<>();
        long startMs = 0;
        while (startMs < totalDurationMs) {
            long remainingMs = totalDurationMs - startMs;
            long endMs = segmentDurationMs >= remainingMs
                    ? totalDurationMs
                    : startMs + segmentDurationMs;
            segments.add(new Segment(startMs, endMs));
            startMs = endMs;
        }
        return Collections.unmodifiableList(segments);
    }

    public static List<Segment> trim(long totalDurationMs, long startMs, long endMs) {
        if (totalDurationMs <= 0) {
            throw new IllegalArgumentException("La durée totale doit être positive.");
        }
        if (startMs < 0 || endMs > totalDurationMs || startMs >= endMs) {
            throw new IllegalArgumentException("L'intervalle de rognage est invalide.");
        }
        return Collections.singletonList(new Segment(startMs, endMs));
    }

    public static int count(long totalDurationMs, long segmentDurationMs) {
        if (totalDurationMs <= 0 || segmentDurationMs <= 0) {
            return 0;
        }
        long count = 1 + ((totalDurationMs - 1) / segmentDurationMs);
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    public static final class Segment {
        private final long startMs;
        private final long endMs;

        public Segment(long startMs, long endMs) {
            if (startMs < 0 || endMs <= startMs) {
                throw new IllegalArgumentException("Intervalle invalide.");
            }
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public long getStartMs() {
            return startMs;
        }

        public long getEndMs() {
            return endMs;
        }

        public long getDurationMs() {
            return endMs - startMs;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Segment)) {
                return false;
            }
            Segment segment = (Segment) other;
            return startMs == segment.startMs && endMs == segment.endMs;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startMs, endMs);
        }

        @Override
        public String toString() {
            return "Segment{" + startMs + "-" + endMs + '}';
        }
    }
}

