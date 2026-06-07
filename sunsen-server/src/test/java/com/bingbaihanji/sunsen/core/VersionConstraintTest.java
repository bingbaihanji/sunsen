package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.core.version.VersionConstraint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionConstraintTest {

    @Test
    void caretConstraintMatchesWithinMajor() {
        VersionConstraint c = VersionConstraint.parse("^1.2.0");
        assertTrue(c.matches("1.2.0"));
        assertTrue(c.matches("1.9.9"));
    }

    @Test
    void caretConstraintRejectsDifferentMajor() {
        VersionConstraint c = VersionConstraint.parse("^1.2.0");
        assertFalse(c.matches("2.0.0"));
        assertFalse(c.matches("0.9.0"));
    }

    @Test
    void caretConstraintRejectsBeforeBase() {
        VersionConstraint c = VersionConstraint.parse("^1.2.0");
        assertFalse(c.matches("1.1.9"));
    }

    @Test
    void tildeConstraintMatchesWithinMinor() {
        VersionConstraint c = VersionConstraint.parse("~1.2.0");
        assertTrue(c.matches("1.2.0"));
        assertTrue(c.matches("1.2.9"));
    }

    @Test
    void tildeConstraintRejectsDifferentMinor() {
        VersionConstraint c = VersionConstraint.parse("~1.2.0");
        assertFalse(c.matches("1.3.0"));
        assertFalse(c.matches("2.0.0"));
    }

    @Test
    void rangeConstraintAcceptsWithinRange() {
        VersionConstraint c = VersionConstraint.parse(">=1.0.0 <2.0.0");
        assertTrue(c.matches("1.0.0"));
        assertTrue(c.matches("1.5.0"));
        assertTrue(c.matches("1.9.9"));
    }

    @Test
    void rangeConstraintRejectsOutsideRange() {
        VersionConstraint c = VersionConstraint.parse(">=1.0.0 <2.0.0");
        assertFalse(c.matches("0.9.0"));
        assertFalse(c.matches("2.0.0"));
    }

    @Test
    void exactConstraintMatchesOnlyExact() {
        VersionConstraint c = VersionConstraint.parse("1.2.3");
        assertTrue(c.matches("1.2.3"));
        assertFalse(c.matches("1.2.4"));
        assertFalse(c.matches("1.3.0"));
    }

    @Test
    void notEqualConstraint() {
        VersionConstraint c = VersionConstraint.parse("!=1.0.0");
        assertFalse(c.matches("1.0.0"));
        assertTrue(c.matches("1.0.1"));
        assertTrue(c.matches("2.0.0"));
    }

    @Test
    void nullExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> VersionConstraint.parse(null));
    }

    @Test
    void blankExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> VersionConstraint.parse("  "));
    }

    @Test
    void combinedConstraints() {
        VersionConstraint c = VersionConstraint.parse(">=1.0.0 <=1.5.0");
        assertTrue(c.matches("1.0.0"));
        assertTrue(c.matches("1.5.0"));
        assertFalse(c.matches("1.5.1"));
    }
}
