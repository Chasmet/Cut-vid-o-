package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class FolderNoteRepositoryTest {

    @Test
    public void normalizationKeepsUsefulFormattingAndRemovesControlCharacters() {
        String normalized = FolderNoteRepository.normalizeText(
                "  Publié sur YouTube\r\nÀ reprendre\u0000\t#Shorts  "
        );

        assertEquals("Publié sur YouTube\nÀ reprendre\t#Shorts", normalized);
        assertFalse(normalized.contains("\u0000"));
    }

    @Test
    public void normalizationLimitsVeryLongNotes() {
        StringBuilder note = new StringBuilder();
        for (int index = 0; index < FolderNoteRepository.MAX_NOTE_LENGTH + 20; index++) {
            note.append('a');
        }

        assertEquals(
                FolderNoteRepository.MAX_NOTE_LENGTH,
                FolderNoteRepository.normalizeText(note.toString()).length()
        );
    }

    @Test
    public void nullNoteBecomesEmptyText() {
        assertEquals("", FolderNoteRepository.normalizeText(null));
    }
}
