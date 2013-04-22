package org.seacourt.build

import sbt._
import Keys._

/**
  * A default/example build setup with the following axes:
  *
  * 1. Release/Debug
  * 2. Gcc/Clang
  * 3. LinuxPC/BeagleBone
  *
  * Not all combinations of the above make sense, only the ones that do should
  * be added to the configurations Set
  */

object NativeDefaultBuild
{
    sealed trait DebugOptLevel
    case object Release extends DebugOptLevel
    case object Debug   extends DebugOptLevel
    
    sealed trait NativeCompiler  
    case object Gcc     extends NativeCompiler
    case object Clang   extends NativeCompiler
    case object VSCl    extends NativeCompiler
    
    sealed trait TargetPlatform
    case object LinuxPC     extends TargetPlatform
    case object WindowsPC   extends TargetPlatform
    case object BeagleBone  extends TargetPlatform
}

class NativeDefaultBuild extends NativeBuild
{
    import NativeDefaultBuild._ 
    
    case class BuildType( debugOptLevel : DebugOptLevel, compiler : NativeCompiler, targetPlatform : TargetPlatform ) extends BuildTypeTrait
    {
        def name        = debugOptLevel.toString + "_" + compiler.toString + "_" + targetPlatform.toString
        def pathDirs    = Seq( debugOptLevel.toString, compiler.toString, targetPlatform.toString )
    }
    
    lazy val gccDefault = new GccCompiler(
        toolPaths           = Seq( file("/usr/bin/") ),
        defaultIncludePaths = Seq(),
        defaultLibraryPaths = Seq(),
        compilerExe         = file("g++-4.7"),
        archiverExe         = file("ar"),
        linkerExe           = file("g++-4.7") )
        
    lazy val clangDefault = new GccCompiler(
        toolPaths           = Seq( file("/home/alex.wilson/tmp/clang/clang+llvm-3.2-x86_64-linux-ubuntu-12.04/bin"), file("/usr/bin/") ),
        defaultIncludePaths = Seq( file("/usr/include/x86_64-linux-gnu/c++/4.7") ),
        defaultLibraryPaths = Seq(),
        compilerExe         = file("clang++"),
        archiverExe         = file("ar"),
        linkerExe           = file("clang++") )


    lazy val vsDefault = new VSCompiler(
        toolPaths           = Seq(
            file("c:/Program Files (x86)/Microsoft SDKs/Windows/v7.0A/Bin"),
            file("c:/Program Files (x86)/Microsoft Visual Studio 10.0/VC/Bin"),
            file("c:/Program Files (x86)/Microsoft Visual Studio 10.0/Common7/IDE")
        ),
        defaultIncludePaths = Seq(
            file("c:/Program Files (x86)/Microsoft SDKs/Windows/v7.0A/Include"),
            file("c:/Program Files (x86)/Microsoft Visual Studio 10.0/VC/include")
        ),
        defaultLibraryPaths = Seq(
            file("c:/Program Files (x86)/Microsoft SDKs/Windows/v7.0A/Lib"),
            file("c:/Program Files (x86)/Microsoft Visual Studio 10.0/VC/lib")
        ),
        compilerExe         = file("cl.exe"),
        archiverExe         = file("link.exe"),
        linkerExe           = file("link.exe") )

    // How do we override these paths etc in user builds? I guess by deriving from default build and overriding configurations?
    
    override lazy val configurations = Set[Environment](
        new Environment( new BuildType( Release, Gcc, LinuxPC ), gccDefault.copy( compileDefaultFlags=Seq("-std=c++11", "-O2", "-Wall", "-Wextra", "-DLINUX", "-DRELEASE", "-DGCC") ) ),
        new Environment( new BuildType( Debug, Gcc, LinuxPC ), gccDefault.copy( compileDefaultFlags=Seq("-std=c++11", "-g", "-Wall", "-Wextra", "-DLINUX", "-DDEBUG", "-DGCC") ) ),
        new Environment( new BuildType( Release, Clang, LinuxPC ), clangDefault.copy( compileDefaultFlags=Seq("-std=c++11", "-O2", "-Wall", "-Wextra", "-DLINUX", "-DRELEASE", "-DCLANG") ) ),
        new Environment( new BuildType( Debug, Clang, LinuxPC ), clangDefault.copy( compileDefaultFlags=Seq("-std=c++11", "-g", "-Wall", "-Wextra", "-DLINUX", "-DDEBUG", "-DCLANG") ) ),
	new Environment( new BuildType( Debug, VSCl, WindowsPC ), vsDefault.copy( compileDefaultFlags=Seq("-DWIN32", "-DDEBUG", "-DVS") ) )
    )
}
