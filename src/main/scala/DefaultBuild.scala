package org.seacourt.build

import sbt._
import Keys._
import com.typesafe.config.ConfigFactory

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
}

class NativeDefaultBuild extends NativeBuild
{
    import NativeDefaultBuild._
    import scala.collection.JavaConversions._
    
    case class BuildType( debugOptLevel : DebugOptLevel, compiler : NativeCompiler, targetPlatform : TargetPlatform ) extends BuildTypeTrait
    {
        def name        = debugOptLevel.toString + "_" + compiler.toString + "_" + targetPlatform.toString
        def pathDirs    = Seq( debugOptLevel.toString, compiler.toString, targetPlatform.toString )
    }
    
    // TODO: Lots of boiler-plate. Move into the compiler trait
    lazy val gccDefault = new GccCompiler(
        toolPaths           = conf.getStringList("gcc.toolPaths").map( file ),
        defaultIncludePaths = conf.getStringList("gcc.includePaths").map( file ),
        defaultLibraryPaths = conf.getStringList("gcc.libraryPaths").map( file ),
        ccExe               = file( conf.getString("gcc.ccExe") ),
        cxxExe              = file( conf.getString("gcc.cxxExe") ),
        archiverExe         = file( conf.getString("gcc.archiver") ),
        linkerExe           = file( conf.getString("gcc.linker") ) )
        
    lazy val clangDefault = new GccCompiler(
        toolPaths           = conf.getStringList("clang.toolPaths").map( file ),
        defaultIncludePaths = conf.getStringList("clang.includePaths").map( file ),
        defaultLibraryPaths = conf.getStringList("clang.libraryPaths").map( file ),
        ccExe               = file( conf.getString("clang.ccExe") ),
        cxxExe              = file( conf.getString("clang.cxxExe") ),
        archiverExe         = file( conf.getString("clang.archiver") ),
        linkerExe           = file( conf.getString("clang.linker") ) )


    lazy val vsDefault = new VSCompiler(
        toolPaths           = conf.getStringList("vscl.toolPaths").map( file ),
        defaultIncludePaths = conf.getStringList("vscl.includePaths").map( file ),
        defaultLibraryPaths = conf.getStringList("vscl.libraryPaths").map( file ),
        compilerExe         = file( conf.getString("vscl.compiler") ),
        archiverExe         = file( conf.getString("vscl.archiver") ),
        linkerExe           = file( conf.getString("vscl.linker") ) )

    override lazy val configurations = Set[Environment](
        new Environment( new BuildType( Release, Gcc, LinuxPC ), gccDefault.copy(
            ccDefaultFlags=conf.getStringList("gcc.release.ccFlags"),
            cxxDefaultFlags=conf.getStringList("gcc.release.cxxFlags") ) ),
            
        new Environment( new BuildType( Debug, Gcc, LinuxPC ), gccDefault.copy(
            ccDefaultFlags=conf.getStringList("gcc.debug.ccFlags"),
            cxxDefaultFlags=conf.getStringList("gcc.debug.cxxFlags") ) ),
            
        new Environment( new BuildType( Release, Clang, LinuxPC ), clangDefault.copy(
            ccDefaultFlags=conf.getStringList("clang.release.ccFlags"),
            cxxDefaultFlags=conf.getStringList("clang.release.cxxFlags") ) ),
        
        new Environment( new BuildType( Debug, Clang, LinuxPC ), clangDefault.copy(
            ccDefaultFlags=conf.getStringList("clang.debug.ccFlags"),
            cxxDefaultFlags=conf.getStringList("clang.debug.cxxFlags") ) ),
        
        new Environment( new BuildType( Release, VSCl, WindowsPC ), vsDefault.copy(
            ccDefaultFlags=conf.getStringList("vscl.release.ccFlags"),
            cxxDefaultFlags=conf.getStringList("vscl.release.cxxFlags") ) ),
        
        new Environment( new BuildType( Debug, VSCl, WindowsPC ), vsDefault.copy(
            ccDefaultFlags=conf.getStringList("vscl.debug.ccFlags"),
            cxxDefaultFlags=conf.getStringList("vscl.debug.cxxFlags") ) )
    )
}


