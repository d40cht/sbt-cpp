import sbt._
import Keys._
import org.seacourt.build._

object TestBuild extends NativeDefaultBuild
{
    lazy val check = NativeProject( "helloworld", file("./"), NativeProject.nativeExeSettings )
}

