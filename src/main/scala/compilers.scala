package org.seacourt.build

import sbt._
import Keys._

import com.typesafe.config.{Config}

import scala.collection.{mutable, immutable}

/**
  * Gcc and compatible (e.g. Clang) compilers
  */
case class GccLikeCompiler( override val config : Config, override val buildTypeTrait : BuildTypeTrait ) extends CompilerWithConfig with CompilationProcess
{
    def findHeaderDependencies( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>
    
        val tmpDepFile = buildDirectory / (sourceFile.base + ".dt")
        val depCmd = Seq[String]( ccExe.toString, "-MF", tmpDepFile.toString, "-M", sourceFile.toString ) ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ systemIncludePaths.map( ip => "-isystem" + ip.toString )
        
        val depResult = runProcess( log, depCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
        
        // Strip off any trailing backslash characters from the output
        val depFileLines = IO.readLines( tmpDepFile ).map( _.replace( "\\", "" ) )
    
        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.flatMap( _.split(" ").drop(1) ).map( x => new File(x.trim) )
        
        IO.write( depFile, allFiles.mkString("\n") )
        
        reportFileGenerated( log, depFile, quiet )
    }
    
    def ccCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val buildCmd = Seq[String]( ccExe.toString, "-fPIC", "-c", "-o", outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ systemIncludePaths.map( ip => "-isystem" + ip.toString )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
        
        reportFileGenerated( log, outputFile, quiet )
    }
    
    def cxxCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".o") )
    { outputFile =>
    
        val buildCmd = Seq[String]( cxxExe.toString, "-fPIC", "-c", "-o", outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.map( ip => "-I" + ip.toString ) ++ systemIncludePaths.map( ip => "-isystem" + ip.toString )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
        
        reportFileGenerated( log, outputFile, quiet )
    }
    
    def buildStaticLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], linkFlags : Seq[String], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / (libName + ".a") )
        { outputFile =>
        
            val arCmd = Seq[String]( archiverExe.toString, "-c", "-r", outputFile.toString ) ++ linkFlags ++ objectFiles.map( _.toString )

            runProcess( log, arCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
            
            reportFileGenerated( log, outputFile, quiet )
        }
        
    def buildSharedLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], linkPaths : Seq[File], linkLibraries : Seq[String], linkFlags : Seq[String], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / (libName + ".so") )
        { outputFile =>
        
            val cmd = Seq[String]( cxxExe.toString, "-shared", "-o", outputFile.toString ) ++ linkFlags ++ objectFiles.map( _.toString ) ++ linkPaths.map( lp => "-L" + lp ) ++ linkLibraries.map( ll => "-l" + ll )

            runProcess( log, cmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
            
            reportFileGenerated( log, outputFile, quiet )
        }
        
    def buildExecutable( log : Logger, buildDirectory : File, exeName : String, linkFlags : Seq[String], linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
            val linkCmd = Seq[String]( linkerExe.toString, "-o" + outputFile.toString ) ++ linkFlags ++ inputFiles.map( _.toString ) ++ linkPaths.map( lp => "-L" + lp ) ++ linkLibraries.map( ll => "-l" + ll )
            
            runProcess( log, linkCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(":")), quiet )
            
            reportFileGenerated( log, outputFile, quiet )
        }
}

/**
  * Visual studio cl.exe compiler
  */
case class VSCompiler( override val config : Config, override val buildTypeTrait : BuildTypeTrait ) extends CompilerWithConfig with CompilationProcess
{   
    def findHeaderDependencies( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".d") )
    { depFile =>

        val depCmd = Seq[String]( ccExe.toString, "/nologo", "/c", "/showIncludes", sourceFile.toString ) ++ compilerFlags ++ includePaths.flatMap( ip => Seq("/I", ip.toString ) ) ++ systemIncludePaths.flatMap( ip => Seq("/I", ip.toString) )
        val depResult = runProcess( log, depCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet=true )

        // Strip off any trailing backslash characters from the output
        val prefix = "Note: including file:"
        val depFileLines = depResult.stdout.split("\n").filter( _.startsWith(prefix) ).map( _.drop( prefix.size ).trim )

        // Drop the first column and split on spaces to get all the files (potentially several per line )
        val allFiles = depFileLines.map( x => new File(x) )

        IO.write( depFile, allFiles.mkString("\n") )

        reportFileGenerated( log, depFile, quiet )
    }

    def ccCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".obj") )
    { outputFile =>

        val buildCmd = Seq[String]( ccExe.toString, "/nologo", "/c", "/EHsc", "/Fo" + outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.flatMap( ip => Seq("/I", ip.toString) ) ++ systemIncludePaths.flatMap( ip => Seq("/I", ip.toString) )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

        reportFileGenerated( log, outputFile, quiet )
    }
    
    def cxxCompileToObj( log : Logger, buildDirectory : File, includePaths : Seq[File], systemIncludePaths : Seq[File], sourceFile : File, compilerFlags : Seq[String], quiet : Boolean ) = FunctionWithResultPath( buildDirectory / (sourceFile.base + ".obj") )
    { outputFile =>

        val buildCmd = Seq[String]( cxxExe.toString, "/nologo", "/c", "/EHsc", "/Fo" + outputFile.toString, sourceFile.toString ) ++ compilerFlags ++ includePaths.flatMap( ip => Seq("/I", ip.toString) ) ++ systemIncludePaths.flatMap( ip => Seq("/I", ip.toString) )

        runProcess( log, buildCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

        reportFileGenerated( log, outputFile, quiet )
    }

    def buildStaticLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], linkFlags : Seq[String], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / (libName + ".lib") )
        { outputFile =>

            val arCmd = Seq[String]( archiverExe.toString, "/nologo", "/OUT:" + outputFile.toString ) ++ linkFlags ++ objectFiles.map( _.toString )
            
            runProcess( log, arCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

            reportFileGenerated( log, outputFile, quiet )
        }

    def buildSharedLibrary( log : Logger, buildDirectory : File, libName : String, objectFiles : Seq[File], linkPaths : Seq[File], linkLibraries : Seq[String], linkFlags : Seq[String], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / ("lib" + libName + ".so") )
        { outputFile =>

            val cmd = Seq[String]( cxxExe.toString, "/nologo", "/DLL", "/OUT:" + outputFile.toString ) ++ linkFlags ++ objectFiles.map( _.toString )

            runProcess( log, cmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

            reportFileGenerated( log, outputFile, quiet )
        }

    def buildExecutable( log : Logger, buildDirectory : File, exeName : String, linkFlags : Seq[String], linkPaths : Seq[File], linkLibraries : Seq[String], inputFiles : Seq[File], quiet : Boolean ) =
        FunctionWithResultPath( buildDirectory / exeName )
        { outputFile =>
            val linkCmd = Seq[String]( linkerExe.toString, "/nologo", "/OUT:" + outputFile.toString ) ++ inputFiles.map( _.toString ) ++ linkPaths.map( lp => "/LIBPATH:" + lp ) ++ linkLibraries.map( ll => "-l" + ll )

            runProcess( log, linkCmd, buildDirectory, Seq("PATH" -> toolPaths.mkString(";")), quiet )

            reportFileGenerated( log, outputFile, quiet )
        }
}


