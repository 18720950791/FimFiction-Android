package at.yawk.fimfiction.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.net.URL;
import java.util.*;
import lombok.extern.log4j.Log4j;

/**
 * Helper class that provides easy caching for downloadable images.
 *
 * @author Yawkat
 */
@Log4j
public class ImageCache {
    private final File dir;

    /** Default extension used when the URL does not carry a recognizable image extension. */
    static final String DEFAULT_EXTENSION = ".img";

    /** Set of recognized image extensions (without leading dot, all lowercase). */
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "webp"));

    ImageCache(File dir) { this.dir = dir; }

    public synchronized Bitmap getImage(URL url) {
        Bitmap b = getCachedImage(url);
        if (b != null) { return b; }
        try {
            loadImage(url);
        } catch (Exception e) { log.warn("Could not download image " + file(url), e); }
        return getCachedImage(url);
    }

    public synchronized Bitmap getCachedImage(URL url) {
        try {
            if (file(url).exists()) {
                return BitmapFactory.decodeFile(file(url).getAbsolutePath());
            }
        } catch (Exception e) { log.warn("Could not load image " + file(url) + " from cache", e); }
        return null;
    }

    /**
     * Returns a stable cache file for the given URL, always located directly inside the
     * configured cache directory. The file name is an 8-character uppercase hex hash
     * followed by a safe image extension.
     */
    public File file(URL url) {
        String name = padLeftZeros(Integer.toHexString(url.hashCode()).toUpperCase()) + extension(url);
        return new File(dir, name);
    }

    /**
     * Derives a safe file extension (including the leading dot) for the cache file of the
     * given URL.
     *
     * <p>Only the last path segment is inspected for a dot. The candidate extension is
     * accepted only when it matches a known image extension; otherwise the stable
     * {@link #DEFAULT_EXTENSION default} is returned. Query parameters, fragments,
     * directory dots, and path-traversal sequences never influence the result.</p>
     */
    static String extension(URL url) {
        // getPath() excludes query string and fragment -- only the URL path is considered.
        String path = url.getPath();
        if (path == null || path.isEmpty()) {
            return DEFAULT_EXTENSION;
        }

        // Isolate the last path segment (everything after the final '/').
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = (lastSlash == -1) ? path : path.substring(lastSlash + 1);

        // Empty segment (trailing slash or root path) -> default.
        if (lastSegment.isEmpty()) {
            return DEFAULT_EXTENSION;
        }

        // Look for a dot strictly within the last segment.
        int dot = lastSegment.lastIndexOf('.');
        if (dot == -1 || dot == lastSegment.length() - 1) {
            // No dot at all, or dot is the very last character (e.g. "file.") -> default.
            return DEFAULT_EXTENSION;
        }

        String candidate = lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT);

        // Only accept recognized image extensions; reject everything else (e.g. .sh, .html).
        if (IMAGE_EXTENSIONS.contains(candidate)) {
            return "." + candidate;
        }
        return DEFAULT_EXTENSION;
    }

    public static String padLeftZeros(String i) {
        StringBuilder builder = new StringBuilder("00000000");
        builder.replace(8 - i.length(), 8, i);
        return builder.toString();
    }

    private void loadImage(URL url) throws IOException {
        File target = file(url);
        target.getParentFile().mkdirs();
        InputStream i = url.openStream();
        try {
            OutputStream o = new FileOutputStream(target);
            boolean successful = false;
            try {
                byte[] buf = new byte[1024];
                int len;
                while ((len = i.read(buf)) > 0) { o.write(buf, 0, len); }
                successful = true;
            } finally {
                o.close();
                if (!successful && target.exists()) {
                    target.delete();
                }
            }
        } finally {
            i.close();
        }
    }
}
