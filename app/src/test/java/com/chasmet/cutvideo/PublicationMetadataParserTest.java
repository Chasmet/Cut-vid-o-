package com.chasmet.cutvideo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PublicationMetadataParserTest {

    @Test
    public void parsesFrenchChatGptSections() {
        PublicationMetadataParser.ParsedMetadata parsed = PublicationMetadataParser.parse(
                "**Titre :** Une lumière dans le ciel\n"
                        + "Description : Une observation étonnante.\n"
                        + "Deuxième ligne de la description.\n"
                        + "Hashtags : #UFO #Mystère"
        );

        assertEquals("Une lumière dans le ciel", parsed.getTitle());
        assertEquals(
                "Une observation étonnante.\nDeuxième ligne de la description.",
                parsed.getDescription()
        );
        assertEquals("#UFO #Mystère", parsed.getHashtags());
    }

    @Test
    public void parsesUnlabelledChatGptCard() {
        PublicationMetadataParser.ParsedMetadata parsed = PublicationMetadataParser.parse(
                "Toute la France chante « On a gagné » 🇫🇷 #Shorts\n"
                        + "\n"
                        + "Le morceau de CHKNoirshadow consacré à la victoire des Bleus.\n"
                        + "\n"
                        + "https://youtu.be/RrdmIujgbd0?is=gpsp25eJvOKKQDPu\n"
                        + "\n"
                        + "#Shorts #RapFrancais\n"
                        + "#EquipeDeFrance"
        );

        assertEquals(
                "Toute la France chante « On a gagné » 🇫🇷 #Shorts",
                parsed.getTitle()
        );
        assertEquals(
                "Le morceau de CHKNoirshadow consacré à la victoire des Bleus.\n\n"
                        + "https://youtu.be/RrdmIujgbd0?is=gpsp25eJvOKKQDPu",
                parsed.getDescription()
        );
        assertEquals("#Shorts #RapFrancais #EquipeDeFrance", parsed.getHashtags());
    }

    @Test
    public void keepsDescriptionParagraphsAndFinalHashtagBlock() {
        PublicationMetadataParser.ParsedMetadata parsed = PublicationMetadataParser.parse(
                "Les Bleus sont champions du monde 🔵⚪🔴 #Shorts\n"
                        + "\n"
                        + "Un extrait rempli de fierté, de football et de célébration.\n"
                        + "\n"
                        + "Clip complet :\n"
                        + "https://youtu.be/RrdmIujgbd0\n"
                        + "\n"
                        + "#Shorts #LesBleus #OnAGagne"
        );

        assertEquals(
                "Les Bleus sont champions du monde 🔵⚪🔴 #Shorts",
                parsed.getTitle()
        );
        assertEquals(
                "Un extrait rempli de fierté, de football et de célébration.\n\n"
                        + "Clip complet :\nhttps://youtu.be/RrdmIujgbd0",
                parsed.getDescription()
        );
        assertEquals("#Shorts #LesBleus #OnAGagne", parsed.getHashtags());
    }

    @Test
    public void singleUnlabelledLineBecomesTitle() {
        PublicationMetadataParser.ParsedMetadata parsed = PublicationMetadataParser.parse(
                "Une publication sans rubriques"
        );

        assertEquals("Une publication sans rubriques", parsed.getTitle());
        assertEquals("", parsed.getDescription());
        assertEquals("", parsed.getHashtags());
    }
}
