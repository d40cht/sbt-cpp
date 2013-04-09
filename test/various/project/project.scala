import sbt._
import Keys._

import org.seacourt.build._
import org.seacourt.build.NativeBuild._
import org.seacourt.build.NativeDefaultBuild._


object TestBuild extends NativeDefaultBuild
{
    lazy val checkLib = ProjectRef( file("../utility"), "check" )
    
    lazy val library1 = StaticLibrary( "library1", file( "./library1" ), Seq() )
        .nativeDependsOn( checkLib )
        
    lazy val library2 = SharedLibrary( "library2", file( "./library2" ),
        Seq(
            cppCompileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.debugOptLevel match
                {
                    case Debug      => Seq("-D", "THING=1")
                    case Release    => Seq("-D", "THING=2")
                }
            },
            
            cppCompileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.compiler match
                {
                    case Gcc        => Seq("-D", "COMPILER=\"GnueyGoodness\"")
                    case Clang      => Seq("-D", "COMPILER=\"AppleTart\"")
                }
            },
            
            cppCompileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.targetPlatform match
                {
                    case LinuxPC    => Seq("-D", "TARGET_PLATFORM=\"x86LinusLand\"")
                    case BeagleBone => Seq("-D", "TARGET_PLATFORM=\"Armless\"")
                }
            }
        ) )
        .nativeDependsOn( checkLib, library1 )
        
    //lazy val 

}

object ScalaLib extends NativeDefaultBuild
{
    import NativeBuild.exportedLibs
    
    lazy val standardSettings = Defaults.defaultSettings
    
     lazy val scalaJNA = Project(
        id="scalaJNA",
        base=file("scalajna"),
        settings=standardSettings ++ Seq[Sett](
            scalaVersion        := "2.9.2",
            
            libraryDependencies += "org.scalatest" %% "scalatest" % "1.6.1",
            
            (test in Test)      <<= (test in Test).dependsOn( exportedLibs in TestBuild.library2 ),
            
            // Needed to set the build environment
            commands            += setBuildConfigCommand
        )
    )
    .dependsOn( TestBuild.library2 )
}

