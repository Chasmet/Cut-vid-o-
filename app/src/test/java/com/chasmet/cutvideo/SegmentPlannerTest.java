package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class SegmentPlannerTest {

    @Test
    public void splitCreatesRemainderSegment() {
        List<SegmentPlanner.Segment> segments = SegmentPlanner.split(31_000L, 15_000L);

        assertEquals(3, segments.size());
        assertEquals(new SegmentPlanner.Segment(0L, 15_000L), segments.get(0));
        assertEquals(new SegmentPlanner.Segment(15_000L, 30_000L), segments.get(1));
        assertEquals(new SegmentPlanner.Segment(30_000L, 31_000L), segments.get(2));
    }

    @Test
    public void splitDoesNotCreateEmptySegmentOnExactDivision() {
        List<SegmentPlanner.Segment> segments = SegmentPlanner.split(30_000L, 15_000L);

        assertEquals(2, segments.size());
        assertEquals(new SegmentPlanner.Segment(15_000L, 30_000L), segments.get(1));
    }

    @Test
    public void splitKeepsShortVideoAsOneSegment() {
        List<SegmentPlanner.Segment> segments = SegmentPlanner.split(8_000L, 90_000L);

        assertEquals(1, segments.size());
        assertEquals(new SegmentPlanner.Segment(0L, 8_000L), segments.get(0));
    }

    @Test
    public void trimCreatesChosenRange() {
        List<SegmentPlanner.Segment> segments = SegmentPlanner.trim(
                60_000L,
                10_000L,
                42_000L
        );

        assertEquals(1, segments.size());
        assertEquals(new SegmentPlanner.Segment(10_000L, 42_000L), segments.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void trimRejectsReversedRange() {
        SegmentPlanner.trim(60_000L, 20_000L, 10_000L);
    }

    @Test
    public void countMatchesSplitPlan() {
        assertEquals(4, SegmentPlanner.count(181_000L, 60_000L));
    }
}

