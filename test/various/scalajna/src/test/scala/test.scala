import org.scalatest.FunSuite



class JNATest extends FunSuite
{
    test("test")
    {
        assert( 1 === 1 )
        
        //println( com.sun.jna.Platform.RESOURCE_PREFIX )
        
        val i = SharedLibraryNative.INSTANCE
        assert( i.add( 4, 5 ) === 9 )
        assert( 1 === 1 )
    }
}



