package cn.diandian.counter;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    private static final String HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String APK = UpdateManifest.REPOSITORY + "/releases/download/v1.1.0/diandian-counter-v1.1.0.apk";
    private UpdateManifest valid(long code) throws IOException { return new UpdateManifest(code, "1.1.0", APK, HASH, 50000, 26, "增加在线更新"); }
    @Test public void comparesMonotonicVersionCodesWithoutStringOrdering() throws Exception {
        assertTrue(valid(11).newerThan(9)); assertFalse(valid(2).newerThan(2)); assertFalse(valid(1).newerThan(2));
    }
    @Test public void allowsGithubReleaseHttpsAndCdnRedirects() {
        assertTrue(UpdateManifest.secureGithubUrl(APK));
        assertTrue(UpdateManifest.secureGithubUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1?token=short"));
        assertTrue(UpdateManifest.secureGithubUrl("https://objects.githubusercontent.com/github-production-release-asset/1"));
    }
    @Test public void blocksDowngradeCredentialsLookalikeHostsAndUntrustedRedirects() {
        for (String url : new String[]{"http://github.com/a.apk", "https://github.com.evil.example/a.apk", "https://github.com@evil.example/a.apk",
                "https://evil@github.com/a.apk", "https://github.com:444/a.apk", "file:///a.apk", "https://github.com/a.apk#fragment", "https://example.com/a.apk"})
            assertFalse(url, UpdateManifest.secureGithubUrl(url));
    }
    @Test(expected = IOException.class) public void rejectsOtherRepository() throws Exception {
        new UpdateManifest(2, "1.1.0", "https://github.com/attacker/counter/releases/download/v1.1.0/a.apk", HASH, 10, 26, "");
    }
    @Test(expected = IOException.class) public void rejectsHashMissing() throws Exception {
        new UpdateManifest(2, "1.1.0", APK, "", 10, 26, "");
    }
    @Test(expected = IOException.class) public void rejectsVersionAndDownloadTagMismatch() throws Exception {
        new UpdateManifest(2, "1.2.0", APK, HASH, 10, 26, "");
    }
    @Test(expected = IOException.class) public void boundsApkSize() throws Exception {
        new UpdateManifest(2, "1.1.0", APK, HASH, UpdateManifest.MAX_APK_BYTES + 1, 26, "");
    }
    @Test(expected = IOException.class) public void rejectsNegativeCodes() throws Exception { valid(-1); }
    @Test public void sha256MatchesKnownDigest() throws Exception {
        File file = File.createTempFile("diandian-sha256-", ".bin");
        try {
            Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateFiles.sha256(file));
        } finally { assertTrue(file.delete()); }
    }
}
