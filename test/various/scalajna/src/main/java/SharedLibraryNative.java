import com.sun.jna.*;
import com.sun.jna.ptr.*;

public interface SharedLibraryNative extends Library
{
    SharedLibraryNative INSTANCE = (SharedLibraryNative) Native.loadLibrary( "libsharedlibrary1.so", SharedLibraryNative.class );

    int add( int a, int b );
}
