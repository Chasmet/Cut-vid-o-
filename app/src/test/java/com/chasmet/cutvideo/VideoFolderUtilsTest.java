package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoFolderUtilsTest {

    private static final String BASE = "Movies/CutVideo/";

    @Test
    public void rootFilesStayInLegacyFolder() {
        assertEquals(
                VideoFolderUtils.LEGACY_FOLDER_KEY,
                VideoFolderUtils.folderKey("Movies/CutVideo/", BASE)
        );
    }

    @Test
    public void subfolderIsExtractedFromRelativePath() {
        assertEquals(
                "CutVideo_Mon_clip_20260712_174500",
                VideoFolderUtils.folderKey(
                        "Movies/CutVideo/CutVideo_Mon_clip_20260712_174500/",
                        BASE
                )
        );
    }

    @Test
    public void firstSubfolderWinsIfPathIsNested() {
        assertEquals(
                "Traitement_1",
                VideoFolderUtils.folderKey("Movies/CutVideo/Traitement_1/autre/", BASE)
        );
    }

    @Test
    public void generatedFolderGetsReadableNameAndDate() {
        String key = "CutVideo_Mon_clip_20260712_174500";

        assertEquals("Mon clip", VideoFolderUtils.displayName(key));
        assertEquals("12/07/2026 à 17:45", VideoFolderUtils.displayDate(key));
    }

    @Test
    public void unsafeCharactersCannotCreateNestedFolders() {
        String safeName = VideoFolderUtils.safeFolderName("  été / match : final  ");

        assertEquals("été_match_final", safeName);
        assertTrue(!safeName.contains("/"));
    }
}

