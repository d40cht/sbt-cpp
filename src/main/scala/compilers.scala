package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{mutable, immutable}

case class GccCompiler(
    override val toolPaths : Seq[File],
    override val defaultLibraryPaths : Seq[File],
    override val defaultIncludePaths : Seq[File],
    val compilerExe : File,
    val archiverExe : File,
    val linkerExe : File,
    val compileDefaultFlags : Seq[String] = Seq(),
    val linkDefaultFlags : Seq[String] = Seq() ) extends Compiler
{
    private def reportFileGenerated( s : TaskStreams, genFile : File )
    {
        s.log.success( genFile.toString )
    }
    
    def findHeaderDependencies( s : TaskStreams, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>
    
        val includePathArg = includePaths.map( ip => "-I " + ip ).mkString(" ")
        val systemIncludePathArg = systemIncludePaths.map( ip => "-isystem " + ip ).mkString(" ")
        
        val depCmd = Seq[String]( compilerExe.toString, "-M", sourceFile.toString ) ++ compileDefaultFlags ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ (defaultIncludePaths ++ systemIncludePaths).map( ip => "-isystem" + ip.toString )
        
        s.log.debug( "Executing: " + depCmd.mkString(" ") )
        
        val depResult = Process( depCmd, buildDirectory, "PATH" -> toolPaths.mkString(":") ).lines
        
        // Strip off any trailing backslash characters from the output
        val depFileLines = depResult.map( _.replace( "\\", "" ) )
    
        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.flatMap( _.split(" ").drop(1) ).map( x => new File(x.trim) )
        
        IO.write( depFile, allFiles.mkString("\n") )
        
        reportFileGenerated( s, depFile )
    }
    
    def compileToObjectFile( s : TaskStreams, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val buildCmd = Seq[String]( compilerExe.toString, "-fPIC", "-c", "-o", outputFile.toString, sourceFile.toString ) ++ compileDefaultFlags ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ (defaultIncludePaths ++ systemIncludePaths).map( ip => "-isystem" + ip.toString )

        s.log.debug( "Executing: " + buildCmd.mkString(" ") )
        Process( buildCmd, buildDirectory, "PATH" -> toolPaths.mkString(":") ) !!
        
        reportFileGenerated( s, outputFile )
    }
    
    def buildStaticLibrary( s : TaskStreams, buildDirectory : File, libName : String, objectFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".a") )
        { outputFile =>
        
            val arCmd = Seq[String]( archiverExe.toString, "-c", "-r", outputFile.toString ) ++ objectFiles.map( _.toString )
            s.log.debug( "Executing: " + arCmd.mkString(" ") )
            Process( arCmd, buildDirectory, "PATH" -> toolPaths.mkString(":") ) !!
            
            reportFileGenerated( s, outputFile )
        }
        
    def buildSharedLibrary( s : TaskStreams, buildDirectory : File, libName : String, objectFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".so") )
        { outputFile =>
        
            val cmd = Seq[String]( compilerExe.toString, "-shared", "-o", outputFile.toString ) ++ objectFiles.map( _.toString )
            s.log.debug( "Executing: " + cmd.mkString(" ") )
            Process( cmd, buildDirectory, "PATH" -> toolPaths.mkString(":") ) !!
            
            reportFileGenerated( s, outputFile )
        }
        
    def buildExecutable( s : TaskStreams, buildDirectory : File, exeName : String, linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
            val linkCmd = Seq[String]( linkerExe.toString, "-o" + outputFile.toString ) ++ linkDefaultFlags ++ inputFiles.map( _.toString ) ++ linkPaths.map( lp => "-L" + lp ) ++ linkLibraries.map( ll => "-l" + ll )
            s.log.debug( "Executing: " + linkCmd.mkString(" ") )
            
            Process( linkCmd, buildDirectory, "PATH" -> toolPaths.mkString(":") ) !!
            
            reportFileGenerated( s, outputFile )
        }
}


