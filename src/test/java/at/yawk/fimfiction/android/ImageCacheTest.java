package at.yawk.fimfiction.android;

import static org.junit.Assert.*;

import java.io.File;
import java.net.URL;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ImageCache#file(URL)} and {@link ImageCache#extension(URL)}.
 *
 * <p>All tests use {@code 127.0.0.1} as the host so that {@link URL#hashCode()} never
 * triggers a DNS lookup, keeping the test suite hermetic and fast.
 */
public class ImageCacheTest {
    private File dir;
    private ImageCache cache;

    @Before
    public void setUp() {
        dir = new File(System.getProperty("java.io.tmpdir"), "imgcache-test-" + System.nanoTime());
        assertTrue("could not create temp cache dir", dir.mkdirs());
        cache = new ImageCache(dir);
    }

    @After
    public void tearDown() {
        // best-effort cleanup
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) { child.delete(); }
        }
        dir.delete();
    }

    /**
     * Asserts that the cache file for {@code spec} has the expected extension and stays
     * inside the configured cache directory.
     */
    private void check(String spec, String expectedExtension) throws Exception {
        URL url = new URL(spec);

        // 1. The static extension() helper must agree with the expectation.
        assertEquals("extension() for " + spec, expectedExtension, ImageCache.extension(url));

        // 2. The file() method must produce a well-formed cache file.
        File f = cache.file(url);

        // File name must be exactly 8 uppercase hex chars + a clean extension (no separators).
        assertTrue("file name must be <8 hex>.<ext> but was '" + f.getName() + "' for " + spec,
                f.getName().matches("[0-9A-F]{8}\\.[a-z0-9]+"));
        assertEquals("extension on file name for " + spec,
                expectedExtension, f.getName().substring(8));

        // The file must be a direct child of the configured cache directory — never traversed out.
        assertEquals("cache file must live directly inside the cache dir for " + spec,
                dir.getCanonicalFile(), f.getParentFile().getCanonicalFile());
        assertTrue("canonical path must stay under the cache dir for " + spec,
                f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator));
    }

    // -------------------------------------------------------------------------
    // 1. Regression: well-formed image URLs keep their expected extensions.
    // -------------------------------------------------------------------------

    @Test
    public void normalImageExtensionsArePreserved() throws Exception {
        check("http://127.0.0.1/images/pic.png", ".png");
        check("http://127.0.0.1/a/b/c/photo.jpg", ".jpg");
        check("http://127.0.0.1/x.jpeg", ".jpeg");
        check("http://127.0.0.1/anim.gif", ".gif");
        check("http://127.0.0.1/raw.bmp", ".bmp");
        check("http://127.0.0.1/modern.webp", ".webp");
    }

    @Test
    public void extensionIsCaseInsensitive() throws Exception {
        check("http://127.0.0.1/PHOTO.JPG", ".jpg");
        check("http://127.0.0.1/Pic.PnG", ".png");
    }

    // -------------------------------------------------------------------------
    // 2. Query parameters must be ignored — extension comes from the path only.
    // -------------------------------------------------------------------------

    @Test
    public void queryParametersAreStripped() throws Exception {
        check("http://127.0.0.1/avatars/user.png?size=200&v=3", ".png");
        check("http://127.0.0.1/pic.gif?cb=12345", ".gif");
    }

    @Test
    public void extensionOnlyInQueryFallsBackToDefault() throws Exception {
        // The path "/download" has no extension; "?file=image.png" must be ignored.
        check("http://127.0.0.1/download?file=image.png", ImageCache.DEFAULT_EXTENSION);
    }

    // -------------------------------------------------------------------------
    // 3. URLs with no extension use the stable default.
    // -------------------------------------------------------------------------

    @Test
    public void missingExtensionUsesDefault() throws Exception {
        check("http://127.0.0.1/images/avatar", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1", ImageCache.DEFAULT_EXTENSION);
    }

    // -------------------------------------------------------------------------
    // 4. A dot inside a directory name must not be mistaken for a file extension.
    // -------------------------------------------------------------------------

    @Test
    public void dotInDirectoryNameIsIgnored() throws Exception {
        check("http://127.0.0.1/v1.2/images/photo", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/v1.2/images/photo.gif", ".gif");
        check("http://127.0.0.1/a.b.c/d.e.f/final.png", ".png");
    }

    // -------------------------------------------------------------------------
    // 5. A trailing slash leaves an empty last segment -> default extension.
    // -------------------------------------------------------------------------

    @Test
    public void trailingSlashUsesDefault() throws Exception {
        check("http://127.0.0.1/images/", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/a/b/c/", ImageCache.DEFAULT_EXTENSION);
    }

    // -------------------------------------------------------------------------
    // Non-image extensions are never used verbatim.
    // -------------------------------------------------------------------------

    @Test
    public void nonImageExtensionsUseDefault() throws Exception {
        check("http://127.0.0.1/evil.sh", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/page.html", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/setup.exe", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/.htaccess", ImageCache.DEFAULT_EXTENSION);
    }

    // -------------------------------------------------------------------------
    // 6. Malicious / traversal paths must never escape the cache directory.
    // -------------------------------------------------------------------------

    @Test
    public void maliciousPathsStayInsideCacheDir() throws Exception {
        check("http://127.0.0.1/../../../../etc/passwd", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/a/evil.png/../../../../tmp/x", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/foo/..", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/..%2f..%2fevil.sh", ImageCache.DEFAULT_EXTENSION);
        // Even a traversal path that ends in a real image extension stays as a flat file.
        check("http://127.0.0.1/../../../../tmp/pwn.png", ".png");
    }

    // -------------------------------------------------------------------------
    // 7. Read-write consistency: same URL always maps to the same file.
    // -------------------------------------------------------------------------

    @Test
    public void sameUrlAlwaysMapsToSameFile() throws Exception {
        URL url = new URL("http://127.0.0.1/avatars/user.png?size=200");
        File first = cache.file(url);
        File second = cache.file(url);
        assertEquals(first, second);
        assertEquals(first.getName(), second.getName());

        // Equal-but-distinct URL objects must also map to the same cache file.
        URL same = new URL("http://127.0.0.1/avatars/user.png?size=200");
        assertEquals(first.getName(), cache.file(same).getName());
    }
}
