package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.core.version.SemVer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemVerTest {

    @Test
    void parseThreeSegments() {
        SemVer v = SemVer.parse("1.2.3");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
    }

    @Test
    void parseVPrefix() {
        SemVer v = SemVer.parse("v2.0.0");
        assertEquals(2, v.major());
        assertEquals(0, v.minor());
        assertEquals(0, v.patch());
    }

    @Test
    void parseCapitalVPrefix() {
        SemVer v = SemVer.parse("V3.1.0");
        assertEquals(3, v.major());
    }

    @Test
    void parseTwoSegments() {
        SemVer v = SemVer.parse("1.0");
        assertEquals(1, v.major());
        assertEquals(0, v.minor());
        assertEquals(0, v.patch());
    }

    @Test
    void parseWithPrerelease() {
        SemVer v = SemVer.parse("1.2.3-alpha");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
    }

    @Test
    void parseWithBuildMetadata() {
        SemVer v = SemVer.parse("1.0.0+build");
        assertEquals(1, v.major());
        assertEquals(0, v.minor());
        assertEquals(0, v.patch());
    }

    @Test
    void parseNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse(null));
    }

    @Test
    void parseBlankThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("  "));
    }

    @Test
    void parseNonNumericThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("abc"));
    }

    @Test
    void parseSingleNumberThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("1"));
    }

    @Test
    void compareToAscendingOrder() {
        SemVer v000 = SemVer.parse("0.0.0");
        SemVer v100 = SemVer.parse("1.0.0");
        SemVer v101 = SemVer.parse("1.0.1");
        SemVer v110 = SemVer.parse("1.1.0");
        SemVer v200 = SemVer.parse("2.0.0");

        assertTrue(v000.compareTo(v100) < 0);
        assertTrue(v100.compareTo(v101) < 0);
        assertTrue(v101.compareTo(v110) < 0);
        assertTrue(v110.compareTo(v200) < 0);
        assertTrue(v200.compareTo(v100) > 0);
    }

    @Test
    void compareToEqual() {
        SemVer a = SemVer.parse("1.2.3");
        SemVer b = SemVer.parse("1.2.3");
        assertEquals(0, a.compareTo(b));
    }

    @Test
    void toStringReturnsStandardFormat() {
        SemVer v = SemVer.parse("1.2.3");
        assertEquals("1.2.3", v.toString());
    }

    @Test
    void parsePreReleaseSuffixWithDotInVersion() {
        SemVer v = SemVer.parse("1.2.3-alpha.1+build.42");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
    }
}
