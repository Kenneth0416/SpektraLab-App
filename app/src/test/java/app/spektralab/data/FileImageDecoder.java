package app.spektralab.data;

import android.graphics.ImageDecoder;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowImageDecoder;

/** Robolectric's API 29 FD entry point is unimplemented; use its real stream decoder. */
@Implements(ImageDecoder.class)
public class FileImageDecoder extends ShadowImageDecoder {
    @Implementation
    protected static ImageDecoder nCreate(FileDescriptor fd, ImageDecoder.Source source) {
        return ShadowImageDecoder.nCreate(new FileInputStream(fd), new byte[16 * 1024], source);
    }
}
