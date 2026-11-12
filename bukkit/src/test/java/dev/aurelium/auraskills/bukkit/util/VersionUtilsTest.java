package dev.aurelium.auraskills.bukkit.util;

import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VersionUtilsTest {

    @BeforeAll
    static void setUp() {
        MockBukkit.mock();
    }

    @AfterAll
    static void unload() {
        MockBukkit.unmock();
    }

    @Test
    void testGetMajorVersion() {
        assertEquals(20, VersionUtils.getMajorVersion("1.20"));
        assertEquals(21, VersionUtils.getMajorVersion("1.21.1"));
        assertEquals(21, VersionUtils.getMajorVersion("1.21.10"));
        assertEquals(26, VersionUtils.getMajorVersion("26.1"));
        assertEquals(27, VersionUtils.getMajorVersion("27.3.1"));
        assertEquals(26, VersionUtils.getMajorVersion("26.1.2.local"));
        assertEquals(26, VersionUtils.getMajorVersion("26.1.2.local-SNAPSHOT"));
    }

    @Test
    void testGetMinorVersion() {
        assertEquals(0, VersionUtils.getMinorVersion("1.20"));
        assertEquals(1, VersionUtils.getMinorVersion("1.21.1"));
        assertEquals(10, VersionUtils.getMinorVersion("1.21.10"));
        assertEquals(1, VersionUtils.getMinorVersion("26.1"));
        assertEquals(3, VersionUtils.getMinorVersion("27.3.1"));
        assertEquals(1, VersionUtils.getMinorVersion("26.1.2.local"));
        assertEquals(1, VersionUtils.getMinorVersion("26.1.2.local-SNAPSHOT"));
    }

    @Test
    void testGetPatchVersion() {
        assertEquals(0, VersionUtils.getPatchVersion("1.20"));
        assertEquals(0, VersionUtils.getPatchVersion("1.21.1"));
        assertEquals(0, VersionUtils.getPatchVersion("1.21.10"));
        assertEquals(0, VersionUtils.getPatchVersion("26.1"));
        assertEquals(1, VersionUtils.getPatchVersion("27.3.1"));
        assertEquals(12, VersionUtils.getPatchVersion("27.3.12"));
        assertEquals(2, VersionUtils.getPatchVersion("26.1.2.local"));
        assertEquals(2, VersionUtils.getPatchVersion("26.1.2.local-SNAPSHOT"));
    }

    @Test
    void testGetVersionString() {
        assertEquals("1.20.4", VersionUtils.getVersionString("1.20.4-R0.1-SNAPSHOT"));
        assertEquals("26.1.2", VersionUtils.getVersionString("26.1.2.local-SNAPSHOT"));
        assertEquals("26.1.2", VersionUtils.getVersionString("26.1.2-R0.1-SNAPSHOT"));
        assertEquals("26.1.2", VersionUtils.getVersionString("26.1.2.build.8-stable"));
        assertEquals("26.1.2", VersionUtils.getVersionString("git-Folia-8 (MC: 26.1.2)"));
    }
}
