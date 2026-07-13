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
    public void plainTextBecomesDescription() {
        PublicationMetadataParser.ParsedMetadata parsed = PublicationMetadataParser.parse(
                "Une légende libre sans rubriques"
        );

        assertEquals("", parsed.getTitle());
        assertEquals("Une légende libre sans rubriques", parsed.getDescription());
        assertEquals("", parsed.getHashtags());
    }
}
