import sbt._
import Keys._

import org.seacourt.build._
import org.seacourt.build.NativeBuild._
import org.seacourt.build.NativeDefaultBuild._

import scala.collection.{mutable, immutable}




object TestBuild extends NativeDefaultBuild
{
    override def checkEnvironment( log : Logger, env : Environment ) =
    {
        import PlatformChecks._
        
        // Require a working c and cxx compiler
        testCCompiler( log, env.compiler )
        testCXXCompiler( log, env.compiler )
        testHeaderParse( log, env.compiler )

        assert( tryCompileAndLink( log, env.compiler, """
            |#include <zlib.h>
            |
            |int main(int argc, char** argv)
            |{
            |    void* ptr = (void*) &gzread;
            |}""".stripMargin, "test.cpp", linkLibraries=Seq("z") ) )
        
        // Check for a few expected headers and type sizes
        requireHeader( log, env.compiler, "test.c", "stdio.h" )
        requireHeader( log, env.compiler, "test.cpp", "iostream" )
        assert( !testForHeader( log, env.compiler, "test.c", "iostream" ) )
        
        requireSymbol( log, env.compiler, "test.c", "printf", Seq("stdio.h") )
        requireSymbol( log, env.compiler, "test.cpp", "std::cout", Seq("iostream") )
        
        requireTypeSize( log, env.compiler, "test.c", "int32_t", 4, Seq("stdint.h") )
        requireTypeSize( log, env.compiler, "test.c", "int64_t", 8, Seq("stdint.h") )
        
        // Check that a few things that shouldn't exist don't exist
        assert( !testForHeader( log, env.compiler, "test.c", "boggletoop" ) )
        assert( !testForSymbolDeclaration( log, env.compiler, "test.c", "toffeecake", Seq("stdio.h") ) )
        
        assert( !testForTypeSize( log, env.compiler, "test.cpp", "int32_t", 3, Seq("stdint.h") ) )
        assert( !testForTypeSize( log, env.compiler, "test.cpp", "int32_t", 5, Seq("stdint.h") ) )
    }
    
    lazy val config = NativeProject( "config", file("."), Seq(
        exportedIncludeDirectories <+= (streams, compiler, projectBuildDirectory) map { (s, c, pbd) =>
            
            val platformHeaderDir = pbd / "interface"
            val platformConfigFile = platformHeaderDir / "platformconfig.hpp"
            
            HeaderConfigFile( s.log, c, platformConfigFile )
            { hcf =>
            
                hcf.addDefinition( "HAS_ZLIB_H",        PlatformChecks.testForHeader( s.log, c, "test.c", "zlib.h" ).toString )
                hcf.addDefinition( "HAS_MALLOC_H",      PlatformChecks.testForHeader( s.log, c, "test.c", "malloc.h" ).toString )
                hcf.addDefinition( "INT_8_BITS",        PlatformChecks.testForTypeSize( s.log, c, "test.c", "int", 1 ).toString )
                hcf.addDefinition( "INT_32_BITS",       PlatformChecks.testForTypeSize( s.log, c, "test.c", "int", 4 ).toString )
                hcf.addDefinition( "LONG_LONG_64_BITS", PlatformChecks.testForTypeSize( s.log, c, "test.c", "long long", 8 ).toString )
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
        
    lazy val cproject = StaticLibrary( "cproject", file( "cproject" ), Seq(
        compileFlags := Seq( "-x", "c", "-std=c99" )
    ) )
    
    lazy val library1 = StaticLibrary( "library1", file( "library1" ), Seq() )
        .nativeDependsOn( checkLib )
        
    lazy val library2 = SharedLibrary( "library2", file( "library2" ),
        Seq(
            compileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.debugOptLevel match
                {
                    case Debug      => Seq("-D", "THING=1")
                    case Release    => Seq("-D", "THING=2")
                }
            },
            
            compileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.compiler match
                {
                    case Gcc        => Seq("-D", "COMPILER=\"GnueyGoodness\"")
                    case Clang      => Seq("-D", "COMPILER=\"AppleTart\"")
                    case VSCl       => Seq("-D", "COMPILER=\"MircoCroft\"")
                }
            },
            
            compileFlags <++= (buildEnvironment) map
            { be =>
                
                be.conf.targetPlatform match
                {
                    case LinuxPC    => Seq("-D", "TARGET_PLATFORM=\"x86LinusLand\"")
                    case WindowsPC  => Seq("-D", "TARGET_PLATFORM=\"x86PointyClicky\"")
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


