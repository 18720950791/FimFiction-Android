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

    /** Stable extension used when the URL does not carry a recognizable image extension. */
    static final String DEFAULT_EXTENSION = ".img";

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<String>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "webp"));

    public File file(URL url) {
        String name = padLeftZeros(Integer.toHexString(url.hashCode()).toUpperCase()) + extension(url);
        return new File(dir, name);
    }

    /**
     * Derives a safe file extension (including the leading dot) for the cache file of the given URL.
     * <p>
     * The extension is taken only from the last path segment and is accepted only if it is a known
     * image extension; otherwise a stable {@link #DEFAULT_EXTENSION default} is returned. The result
     * never contains path separators or {@code ..}, so the resulting cache file is always a direct
     * child of the cache directory and the URL path can never traverse out of it.
     */
    static String extension(URL url) {
        String path = url.getPath();
        if (path == null) { return DEFAULT_EXTENSION; }
        int slash = path.lastIndexOf('/');
        String lastSegment = slash == -1 ? path : path.substring(slash + 1);
        int dot = lastSegment.lastIndexOf('.');
        if (dot != -1 && dot < lastSegment.length() - 1) {
            String candidate = lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (IMAGE_EXTENSIONS.contains(candidate)) {
                return "." + candidate;
            }
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
