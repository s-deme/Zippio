package dev.zippio;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class ArchiveEngineTest {
    @Test
    public void validatesNormalRelativeNames() throws Exception {
        assertEquals("photos/2026/image.jpg",
                ArchiveEngine.validateArchiveEntryName("photos\\2026\\image.jpg"));
    }

    @Test
    public void rejectsUnsafeArchiveNames() {
        assertThrows(IOException.class,
                () -> ArchiveEngine.validateArchiveEntryName("../outside.txt"));
        assertThrows(IOException.class,
                () -> ArchiveEngine.validateArchiveEntryName("/absolute.txt"));
        assertThrows(IOException.class,
                () -> ArchiveEngine.validateArchiveEntryName("folder//file.txt"));
    }

    @Test
    public void destinationCannotEscapeRoot() {
        File root = new File("build/test-safe-root");
        assertThrows(IOException.class,
                () -> ArchiveEngine.safeDestination(root, "../outside.txt"));
    }

    @Test
    public void choosesExpectedZipCompressionLevels() {
        assertEquals(ArchiveEngine.CompressionProfile.FAST,
                ArchiveEngine.CompressionProfile.fromLabel("高速（短時間）"));
        assertEquals(ArchiveEngine.CompressionProfile.NORMAL,
                ArchiveEngine.CompressionProfile.fromLabel("標準（おすすめ）"));
        assertEquals(ArchiveEngine.CompressionProfile.SMALL,
                ArchiveEngine.CompressionProfile.fromLabel("高圧縮（時間がかかる）"));
    }
}
