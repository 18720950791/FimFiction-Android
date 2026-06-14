package at.yawk.fimfiction.android;

import static org.junit.Assert.*;

import java.io.File;
import java.net.URL;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ImageCache#file(URL)} / {@link ImageCache#extension(URL)}.
 *
 * <p>A literal {@code 127.0.0.1} host is used everywhere so that {@link URL#hashCode()} never
 * triggers a DNS lookup, keeping the tests hermetic and fast. The path is the only part of the
 * URL that influences the chosen extension, so the host is irrelevant to the behaviour exercised
 * here.
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
        // best-effort cleanup; the directory only ever holds files we created
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) { child.delete(); }
        }
        dir.delete();
    }

    /** Asserts the cache file for {@code spec} has the expected extension and stays inside the dir. */
    private void check(String spec, String expectedExtension) throws Exception {
        URL url = new URL(spec);

        // extension() helper agrees with the expectation
        assertEquals("extension() for " + spec, expectedExtension, ImageCache.extension(url));

        File f = cache.file(url);

        // name is exactly 8 hex chars + a clean extension: no separators, no traversal sequences
        assertTrue("file name must be <8 hex>.<ext> but was '" + f.getName() + "' for " + spec,
                f.getName().matches("[0-9A-F]{8}\\.[a-z0-9]+"));
        assertEquals("extension on file name for " + spec, expectedExtension, f.getName().substring(8));

        // the file is always a direct child of the configured cache dir (never traversed out)
        assertEquals("cache file must live directly inside the cache dir for " + spec,
                dir.getCanonicalFile(), f.getParentFile().getCanonicalFile());
        assertTrue("canonical path must stay under the cache dir for " + spec,
                f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator));
    }

    // 1. Existing, well-formed image URLs keep working (regression guard).
    @Test
    public void normalImageExtensionsArePreserved() throws Exception {
        check("http://127.0.0.1/images/pic.png", ".png");
        check("http://127.0.0.1/a/b/c/photo.jpg", ".jpg");
        check("http://127.0.0.1/x.jpeg", ".jpeg");
        check("http://127.0.0.1/anim.gif", ".gif");
        check("http://127.0.0.1/raw.bmp", ".bmp");
        check("http://127.0.0.1/modern.webp", ".webp");
    }

    // Extension matching is case-insensitive but normalised to lower case.
    @Test
    public void extensionIsCaseInsensitive() throws Exception {
        check("http://127.0.0.1/PHOTO.JPG", ".jpg");
        check("http://127.0.0.1/Pic.PnG", ".png");
    }

    // 2a. Query parameters must be ignored; the extension comes from the path only.
    @Test
    public void queryParametersAreStripped() throws Exception {
        check("http://127.0.0.1/avatars/user.png?size=200&v=3", ".png");
        check("http://127.0.0.1/pic.gif?cb=12345", ".gif");
    }

    // 2b. A query that merely *looks* like it has an extension must not leak into the file name.
    @Test
    public void extensionOnlyInQueryFallsBackToDefault() throws Exception {
        check("http://127.0.0.1/download?file=image.png", ImageCache.DEFAULT_EXTENSION);
    }

    // 3. URLs with no extension use the stable default.
    @Test
    public void missingExtensionUsesDefault() throws Exception {
        check("http://127.0.0.1/images/avatar", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1", ImageCache.DEFAULT_EXTENSION);
    }

    // 4. A dot inside a directory name must not be mistaken for the file extension.
    @Test
    public void dotInDirectoryNameIsIgnored() throws Exception {
        check("http://127.0.0.1/v1.2/images/photo", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/v1.2/images/photo.gif", ".gif");
        check("http://127.0.0.1/a.b.c/d.e.f/final.png", ".png");
    }

    // 5. A trailing slash leaves an empty last segment -> default, still inside the dir.
    @Test
    public void trailingSlashUsesDefault() throws Exception {
        check("http://127.0.0.1/images/", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/a/b/c/", ImageCache.DEFAULT_EXTENSION);
    }

    // Non-image extensions are never used verbatim.
    @Test
    public void nonImageExtensionsUseDefault() throws Exception {
        check("http://127.0.0.1/evil.sh", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/page.html", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/setup.exe", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/.htaccess", ImageCache.DEFAULT_EXTENSION);
    }

    // 6. Malicious / traversal-style paths must never escape the cache directory.
    @Test
    public void maliciousPathsStayInsideCacheDir() throws Exception {
        check("http://127.0.0.1/../../../../etc/passwd", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/a/evil.png/../../../../tmp/x", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/foo/..", ImageCache.DEFAULT_EXTENSION);
        check("http://127.0.0.1/..%2f..%2fevil.sh", ImageCache.DEFAULT_EXTENSION);
        // even a traversal path that ends in a real image extension stays a flat file in the dir
        check("http://127.0.0.1/../../../../tmp/pwn.png", ".png");
    }

    // The read path and the write path must resolve to the very same file for a given URL.
    @Test
    public void sameUrlAlwaysMapsToSameFile() throws Exception {
        URL url = new URL("http://127.0.0.1/avatars/user.png?size=200");
        File first = cache.file(url);
        File second = cache.file(url);
        assertEquals(first, second);
        assertEquals(first.getName(), second.getName());

        // equal-but-distinct URL objects map to the same cache file too
        URL same = new URL("http://127.0.0.1/avatars/user.png?size=200");
        assertEquals(first.getName(), cache.file(same).getName());
    }
}
