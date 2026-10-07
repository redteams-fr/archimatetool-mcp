package fr.redteams.archi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AboutInfoTest {

    @Test
    void releaseVersionFromBundleVersion() {
        assertEquals("0.2.0", AboutInfo.releaseVersion("0.2.0.202610071403"));
        assertEquals("1.2.0-rc1", AboutInfo.releaseVersion("1.2.0.202610071403-rc1"));
        assertEquals("1.2.0", AboutInfo.releaseVersion("1.2.0"));
        assertEquals("dev", AboutInfo.releaseVersion("dev"));
    }
}
