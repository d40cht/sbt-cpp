import sbt._
import Keys._
import org.seacourt.build._

object TestBuild extends NativeDefaultBuild
{
    lazy val checkLib = ProjectRef( file("../utility"), "check" )
    
    lazy val library1 = StaticLibrary( "library1", file( "./library1" ), Seq() )
        .nativeDependsOn( checkLib )
        
    lazy val library2 = StaticLibrary( "library2", file( "./library2" ), Seq() )
        .nativeDependsOn( checkLib, library1 )
        
    //lazy val 

}
