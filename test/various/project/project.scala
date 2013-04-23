import sbt._
import Keys._

import org.seacourt.build._
import org.seacourt.build.NativeBuild._
import org.seacourt.build.NativeDefaultBuild._

import scala.collection.{mutable, immutable}

class HeaderConfigFile( private val fileName : File )
{
    private val defs = mutable.ArrayBuffer[(String, String)]()
    
    def addDefinition( name : String ) = defs.append( (name, "") )
    def addDefinition( name : String, value : String ) = defs.append( (name, value) )
    
    def write() =
    {
        IO.write( fileName, defs.map { case (k, v) => "#define %s %s".format(k, v) }.mkString("\n") )
    }
}

object HeaderConfigFile
{
    def apply( log : Logger, compiler : Compiler, fileName : File )( fn : HeaderConfigFile => Unit ) =
    {
        FunctionWithResultPath( fileName )
        { _ =>
            val hcf = new HeaderConfigFile( fileName )
            
            fn( hcf )
            
            
            
            hcf.write()
            
            fileName
        }()
    }
}


object TestBuild extends NativeDefaultBuild
{
    override def checkEnvironment( log : Logger, env : Environment ) =
    {
        // Require a working c and cxx compiler
        PlatformChecks.testCCompiler( log, env.compiler )
        PlatformChecks.testCXXCompiler( log, env.compiler )
        
        true
    }
    
    lazy val config = NativeProject( "config", file("."), Seq(
        exportedIncludeDirectories <+= (streams, compiler, projectBuildDirectory) map { (s, c, pbd) =>
            
            val platformHeaderDir = pbd / "interface"
            val platformConfigFile = platformHeaderDir / "platformconfig.hpp"
            
            HeaderConfigFile( s.log, c, platformConfigFile )
            { hcf =>
            
                hcf.addDefinition( "HAS_ZLIB_H",        PlatformChecks.testForHeader( s.log, c, "zlib.h" ).toString )
                hcf.addDefinition( "HAS_MALLOC_H",      PlatformChecks.testForHeader( s.log, c, "malloc.h" ).toString )
                hcf.addDefinition( "INT_8_BITS",        PlatformChecks.testForTypeSize( s.log, c, "int", 1 ).toString )
                hcf.addDefinition( "INT_32_BITS",       PlatformChecks.testForTypeSize( s.log, c, "int", 4 ).toString )
                hcf.addDefinition( "LONG_LONG_64_BITS", PlatformChecks.testForTypeSize( s.log, c, "long long", 8 ).toString )
            }
        
            platformHeaderDir
        }
    ) )
        
    lazy val checkLib = ProjectRef( file("../utility"), "check" )
    
    lazy val foo = NativeProject( "foo", file("foo"), Seq(
        compile <<= (streams, buildEnvironment) map
        { (s, be) =>
            
            sbt.inc.Analysis.Empty
        } ) )
        
    
    lazy val library1 = StaticLibrary( "library1", file( "library1" ), Seq() )
        .nativeDependsOn( checkLib )
        
    lazy val library2 = SharedLibrary( "library2", file( "library2" ),
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
                    case VSCl       => Seq("-D", "COMPILER=\"MircoCroft\"")
                }
            },
            
            cppCompileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.targetPlatform match
                {
                    case LinuxPC    => Seq("-D", "TARGET_PLATFORM=\"x86LinusLand\"")
                    case WindowsPC  => Seq("-D", "TARGET_PLATFORM=\"x86PointyClicky\"")
                    case BeagleBone => Seq("-D", "TARGET_PLATFORM=\"Armless\"")
                }
            }
        ) )
        .nativeDependsOn( checkLib, library1, config )
        
    lazy val standardSettings = Defaults.defaultSettings
    
    /*lazy val scalaJNA = Project(
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
    .dependsOn( TestBuild.library2 )*/
    
}


