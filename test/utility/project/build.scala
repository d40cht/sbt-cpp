import sbt._
import Keys._
import org.seacourt.build._

object TestBuild extends NativeDefaultBuild( "Utility" )
{
    lazy val check = NativeProject( "check", file("."), NativeProject.staticLibrarySettings )
}

