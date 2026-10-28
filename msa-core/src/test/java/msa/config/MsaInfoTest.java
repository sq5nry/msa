package msa.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MsaInfoTest {

    @Test
    void foundInTheNearestParent(@TempDir Path repo) throws Exception {
        Path info = Files.createDirectories(repo.resolve(MsaInfo.FOLDER));
        Path module = Files.createDirectories(repo.resolve("msa-gui/target"));
        assertTrue(MsaInfo.searchUp(module).isEmpty(), "needs config.txt");
        Files.writeString(info.resolve("config.txt"), "x");
        assertEquals(info, MsaInfo.searchUp(module).orElseThrow());
        assertEquals(info, MsaInfo.searchUp(repo).orElseThrow());
        assertEquals(info, MsaInfo.locate(info.toString()));
        assertThrows(IllegalArgumentException.class, () -> MsaInfo.locate(module.toString()));
    }

    @Test
    void appConfig(@TempDir Path info) throws Exception {
        assertTrue(MsaInfo.AppConfig.load(info).rememberSettings(), "no file: default true");
        Files.writeString(info.resolve(MsaInfo.APP_PROPERTIES), "# x\nrememberSettings = false\n");
        assertFalse(MsaInfo.AppConfig.load(info).rememberSettings());
        Files.writeString(info.resolve(MsaInfo.APP_PROPERTIES), "other=1\n");
        assertTrue(MsaInfo.AppConfig.load(info).rememberSettings());
    }
}
