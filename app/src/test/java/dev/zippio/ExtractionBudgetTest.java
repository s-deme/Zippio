package dev.zippio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public final class ExtractionBudgetTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void policyConstants() {
        assertEquals(4L * 1024 * 1024 * 1024, ExtractionBudget.FILE_LIMIT);
        assertEquals(16L * 1024 * 1024 * 1024, ExtractionBudget.TOTAL_LIMIT);
        assertEquals(100000, ExtractionBudget.ENTRY_LIMIT);
        assertEquals(64L * 1024 * 1024, ExtractionBudget.DICTIONARY_LIMIT);
        assertEquals(66560, ExtractionBudget.MEMORY_LIMIT_KIB);
        assertEquals(256L * 1024 * 1024, ExtractionBudget.FREE_RESERVE);
    }

    @Test public void exactFileAndTotalLimitsAreAccepted() throws Exception {
        File root = temporary.newFolder();
        try (ExtractionBudget budget = new ExtractionBudget(root, 4, 8, 2, 0)) {
            for (String name : new String[]{"one", "two"}) {
                budget.entry();
                try (OutputStream out = budget.output(new File(root, name))) {
                    out.write(new byte[4]);
                }
            }
            budget.commit();
        }
        assertEquals(4, new File(root, "one").length());
        assertEquals(4, new File(root, "two").length());
    }

    @Test public void exceedingFileCleansPartialOutput() throws Exception {
        File root = temporary.newFolder();
        try (ExtractionBudget budget = new ExtractionBudget(root, 4, 8, 2, 0)) {
            budget.entry();
            try (OutputStream out = budget.output(new File(root, "partial"))) {
                out.write(new byte[4]);
                assertThrows(IOException.class, () -> out.write(0));
            }
        }
        assertEquals(0, root.list().length);
    }

    @Test public void exceedingTotalAndEntryLimitsAreRejected() throws Exception {
        File root = temporary.newFolder();
        try (ExtractionBudget budget = new ExtractionBudget(root, 4, 5, 2, 0)) {
            budget.entry();
            try (OutputStream out = budget.output(new File(root, "one"))) { out.write(new byte[4]); }
            budget.entry();
            try (OutputStream out = budget.output(new File(root, "two"))) {
                out.write(0);
                assertThrows(IOException.class, () -> out.write(0));
            }
            assertThrows(IOException.class, budget::entry);
        }
        assertEquals(0, root.list().length);
    }

    @Test public void insufficientSpaceCleansOutputAndKeepsExistingFiles() throws Exception {
        File real = temporary.newFolder();
        File root = new File(real.getPath()) {
            @Override public long getUsableSpace() { return 0; }
        };
        File existing = new File(root, "existing");
        Files.write(existing.toPath(), new byte[]{42});
        try (ExtractionBudget budget = new ExtractionBudget(root, 4, 8, 2, 1)) {
            try (OutputStream out = budget.output(new File(root, "partial"))) {
                assertThrows(IOException.class, () -> out.write(0));
            }
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        assertFalse(new File(root, "partial").exists());
    }

    @Test public void canceledWorkCleansCreatedDirectories() throws Exception {
        File root = temporary.newFolder();
        try {
            try (ExtractionBudget budget = new ExtractionBudget(root, 4, 8, 2, 0)) {
                budget.directory(new File(root, "nested"));
                Thread.currentThread().interrupt();
                assertThrows(IOException.class, budget::entry);
            }
        } finally { Thread.interrupted(); }
        assertEquals(0, root.list().length);
    }

    @Test public void existingDestinationCannotBeOverwritten() throws Exception {
        File root = temporary.newFolder();
        File existing = new File(root, "existing");
        Files.write(existing.toPath(), new byte[]{42});
        try (ExtractionBudget budget = new ExtractionBudget(root, 4, 8, 2, 0)) {
            assertThrows(IOException.class, () -> budget.output(existing));
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
    }

    @Test public void zipStreamingUsesActualOutputLimitAndCleansFailure() throws Exception {
        File archive = temporary.newFile("input.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            zip.putNextEntry(new ZipEntry("日本語/page.txt"));
            zip.write(new byte[9]);
            zip.closeEntry();
        }
        File root = temporary.newFolder();
        assertThrows(IOException.class, () -> ArchiveEngine.extract(archive, root, null,
                new ExtractionBudget(root, 8, 16, 10, 0)));
        assertEquals(0, root.list().length);
        ArchiveEngine.extract(archive, root, null, new ExtractionBudget(root, 9, 9, 10, 0));
        assertEquals(9, new File(root, "日本語/page.txt").length());
    }

    @Test public void sevenZipStreamingRejectsExcessAndCleansCancellation() throws Exception {
        File archive = temporary.newFile("input.7z");
        try(org.apache.commons.compress.archivers.sevenz.SevenZOutputFile out = new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(archive)) {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry = new org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry();
            entry.setName("日本語/page.txt"); out.putArchiveEntry(entry); out.write(new byte[9]); out.closeArchiveEntry();
        }
        File root = temporary.newFolder();
        assertThrows(Exception.class, () -> ArchiveEngine.extract(archive, root, null, new ExtractionBudget(root, 8, 16, 10, 0)));
        assertEquals(0, root.list().length);
        try {
            Thread.currentThread().interrupt();
            assertThrows(Exception.class, () -> ArchiveEngine.extract(archive, root, null, new ExtractionBudget(root, 9, 9, 10, 0)));
        } finally { Thread.interrupted(); }
        assertEquals(0, root.list().length);
        ArchiveEngine.extract(archive, root, null, new ExtractionBudget(root, 9, 9, 10, 0));
        assertEquals(9, new File(root,"日本語/page.txt").length());
    }

    @Test public void rarStreamingRejectsExcessAndKeepsOriginalFiles() throws Exception {
        File archive=temporary.newFile("input.rar");
        try(java.io.InputStream in=getClass().getResourceAsStream("/stored.rar")) { Files.copy(in,archive.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        File root=temporary.newFolder(); File original=new File(root,"keep"); Files.write(original.toPath(),new byte[]{42});
        assertThrows(Exception.class, () -> ArchiveEngine.extract(archive,root,null,new ExtractionBudget(root,1,2,10,0)));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(original.toPath())); assertEquals(1,root.list().length);
        ArchiveEngine.ArchiveInfo info=ArchiveEngine.inspect(archive,null);
        ArchiveEngine.extract(archive,root,null,new ExtractionBudget(root,info.uncompressedBytes,info.uncompressedBytes,10,0));
        assertEquals(info.uncompressedBytes, new File(root,"chapter/page2.png").length()+new File(root,"chapter/page10.png").length());
    }

    @Test public void dictionaryAt64MiBIsAcceptedAndLargerIsRejected() throws Exception {
        File archive = temporary.newFile("dictionary.7z");
        try(org.apache.commons.compress.archivers.sevenz.SevenZOutputFile out = new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(archive)) {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry = new org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry();
            entry.setName("page.txt"); out.putArchiveEntry(entry); out.write(new byte[9]); out.closeArchiveEntry();
        }
        byte[] original = Files.readAllBytes(archive.toPath());
        // LZMA2 uses one dictionary property byte. Recompute both header CRCs after changing it.
        for(int property : new int[]{28,29}) {
            byte[] data = original.clone(); int changed = 0;
            java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int next = Math.toIntExact(32 + header.getLong(12)); int length = Math.toIntExact(header.getLong(20));
            for(int i=next;i<next+length-3;i++) if(data[i]==0x21 && data[i+1]==0x21 && data[i+2]==1) {data[i+3]=(byte)property; changed++;}
            assertEquals(1,changed);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data,next,length); header.putInt(28,(int)crc.getValue());
            crc.reset(); crc.update(data,12,20); header.putInt(8,(int)crc.getValue()); Files.write(archive.toPath(),data);
            File root=temporary.newFolder();
            if(property==28) { ArchiveEngine.extract(archive,root,null); assertEquals(9,new File(root,"page.txt").length()); }
            else { assertThrows(Exception.class, () -> ArchiveEngine.extract(archive,root,null)); assertEquals(0,root.list().length); }
        }
    }
}
