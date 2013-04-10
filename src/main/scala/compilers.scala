package org.seacourt.build

import sbt._
import Keys._

import scala.collection.{mutable, immutable}


case class GccCompiler(
    val compilerPath : File,
    val archiverPath : File,
    val linkerPath : File,
    val compileFlags : String = "",
    val linkFlags : String = "" ) extends Compiler
{
    private def reportFileGenerated( s : TaskStreams, genFile : File )
    {
        s.log.success( genFile.toString )
    }
    
    def findHeaderDependencies( s : TaskStreams, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>
    
        val includePathArg = includePaths.map( ip => "-I " + ip ).mkString(" ")
        val systemIncludePathArg = systemIncludePaths.map( ip => "-isystem " + ip ).mkString(" ")
        val additionalFlags = compilerFlags.mkString(" ")
        val depCmd = "%s %s %s -M %s %s %s".format( compilerPath, compileFlags, additionalFlags, includePathArg, systemIncludePathArg, sourceFile )
        
        s.log.debug( "Executing: " + depCmd )
        val depResult = stringToProcess( depCmd ).lines
        
        // Strip off any trailing backslash characters from the output
        val depFileLines = depResult.map( _.replace( "\\", "" ) )
    
        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.flatMap( _.split(" ").drop(1) ).map( x => new File(x.trim) )
        
        IO.write( depFile, allFiles.mkString("\n") )
        
        reportFileGenerated( s, depFile )
    }
    
    def compileToObjectFile( s : TaskStreams, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String] ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val includePathArg = includePaths.map( ip => "-I " + ip ).mkString(" ")
        val systemIncludePathArg = systemIncludePaths.map( ip => "-isystem " + ip ).mkString(" ")
        val additionalFlags = compilerFlags.mkString(" ")
        val buildCmd = "%s -fPIC %s %s %s %s -c -o %s %s".format( compilerPath, compileFlags, additionalFlags, includePathArg, systemIncludePathArg, outputFile, sourceFile )

        s.log.debug( "Executing: " + buildCmd )
        buildCmd !!
        
        reportFileGenerated( s, outputFile )
    }
    
    def buildStaticLibrary( s : TaskStreams, buildDirectory : File, libName : String, objectFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".a") )
        { outputFile =>
        
            val arCmd = "%s -c -r %s %s".format( archiverPath, outputFile, objectFiles.mkString(" ") )
            s.log.debug( "Executing: " + arCmd )
            arCmd !!
            
            reportFileGenerated( s, outputFile )
        }
        
    def buildSharedLibrary( s : TaskStreams, buildDirectory : File, libName : String, objectFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".so") )
        { outputFile =>
        
            val cmd = "%s -shared -o %s %s".format( compilerPath, outputFile, objectFiles.mkString(" ") )
            s.log.debug( "Executing: " + cmd )
            cmd !!
            
            reportFileGenerated( s, outputFile )
        }
        
    def buildExecutable( s : TaskStreams, buildDirectory : File, exeName : String, linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File] ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
        
            val linkPathArg = linkPaths.map( lp => "-L " + lp ).mkString(" ")
            val libArgs = linkLibraries.map( ll => "-l" + ll ).mkString(" ")
            val linkCmd = "%s %s -o %s %s %s %s".format( linkerPath, linkFlags, outputFile, inputFiles.mkString(" "), linkPathArg, libArgs )
            s.log.debug( "Executing: " + linkCmd )
            linkCmd !!
            
            reportFileGenerated( s, outputFile )
        }
}


